package com.continuuity.gateway.collector;

import com.continuuity.api.flow.flowlet.Event;
import com.continuuity.api.flow.flowlet.Tuple;
import com.continuuity.data.operation.ttqueue.*;
import com.continuuity.flow.definition.impl.FlowStream;
import com.continuuity.flow.flowlet.internal.EventBuilder;
import com.continuuity.flow.flowlet.internal.TupleSerializer;
import com.continuuity.gateway.Constants;
import com.continuuity.gateway.util.NettyRestHandler;
import com.google.common.collect.Maps;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.QueryStringDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This is the http request handler for the rest collector. At this time it only accepts
 * POST requests to send an event to a stream.
 */
public class RestHandler extends NettyRestHandler {

  private static final Logger LOG = LoggerFactory
      .getLogger(RestHandler.class);

  /**
   * The allowed methods for this handler
   */
  HttpMethod[] allowedMethods = { HttpMethod.POST, HttpMethod.GET };

  /**
   * The collector that created this handler. It has collector name and the consumer
   */
  private RestCollector collector;
  /**
   * All the paths have to be of the form http://host:port&lt;pathPrefix>&lt;stream>
   * For instance, if config(prefix="/v0.1/" path="stream/"), then pathPrefix will be
   * "/v0.1/stream/", and a valid request is POST http://host:port/v0.1/stream/mystream
   */
  private String pathPrefix;

  /**
   * Disallow default constructor
   */
  @SuppressWarnings("unused")
  private RestHandler() {
  }

  /**
   * Constructor requires to pass in the collector that created this handler.
   *
   * @param collector The collector that created this handler
   */
  RestHandler(RestCollector collector) {
    this.collector = collector;
    this.pathPrefix = collector.getHttpConfig().getPathPrefix()
        + collector.getHttpConfig().getPathMiddle();
  }

  /**
   * Determines whether an HTTP header should be preserved in the persisted event,
   * and if so returns the (possibly transformed) header name. We pass through
   * all headers that start with the name of destination stream, but we strip of
   * the stream name.
   *
   * @param destinationPrefix The name of the destination stream with . appended
   * @param name              The nameof the header to check
   * @return the name to use for the header if it is perserved, or null otherwise.
   */
  private String isPreservedHeader(String destinationPrefix, String name) {
    if (Constants.HEADER_CLIENT_TOKEN.equals(name)) return name;
    if (name.startsWith(destinationPrefix)) return name.substring(destinationPrefix.length());
    return null;
  }

  private static final int UNKNOWN = 0;
  private static final int ENQUEUE = 1;
  private static final int NEWID = 2;
  private static final int DEQUEUE = 3;
  private static final int META = 4;

  @Override
  public void messageReceived(ChannelHandlerContext context, MessageEvent message) throws Exception {
    HttpRequest request = (HttpRequest) message.getMessage();

    LOG.debug("Request received");

    // we only support POST
    HttpMethod method = request.getMethod();
    if (method != HttpMethod.POST && method != HttpMethod.GET ) {
      LOG.debug("Received a " + method + " request, which is not supported");
      respondNotAllowed(message.getChannel(), allowedMethods);
      return;
    }

    // we do not support a query or parameters in the URL
    QueryStringDecoder decoder = new QueryStringDecoder(request.getUri());
    Map<String, List<String>> parameters = decoder.getParameters();

    int operation = UNKNOWN;
    if (method == HttpMethod.POST)
      operation = ENQUEUE;
    else if (method == HttpMethod.GET) {
      if (parameters == null || parameters.size() == 0)
        operation = META;
      else {
        List<String> qParams = parameters.get("q");
        if (qParams != null && qParams.size() == 1) {
          if ("newConsumer".equals(qParams.get(0)))
            operation = NEWID;
          else if ("dequeue".equals(qParams.get(0)))
            operation = DEQUEUE;
    } } }

    // respond with error for unknown requests
    if (operation == UNKNOWN) {
      LOG.debug("Received an unsupported " + method + " request '" + request.getUri() + "'.");
      respondError(message.getChannel(), HttpResponseStatus.NOT_IMPLEMENTED);
      return;
    }

    if ((operation == ENQUEUE || operation == META) && parameters != null && !parameters.isEmpty()) {
      LOG.debug("Received a request with query parameters, which is not supported");
      respondError(message.getChannel(), HttpResponseStatus.NOT_IMPLEMENTED);
      return;
    }

    // does the path of the URL start with the correct prefix, and is it of the form
    // <flowname> or <flowname</<streamname> after that? Otherwise we will not accept this request.
    String destination = null;
    String path = decoder.getPath();
    if (path.startsWith(this.pathPrefix)) {
      String resourceName = path.substring(this.pathPrefix.length());
      if (resourceName.length() > 0) {
        int pos = resourceName.indexOf('/');
        if (pos < 0) { // flowname
          destination = resourceName;
        } else {
          if (pos + 1 == resourceName.length()) {
            destination = resourceName.substring(pos);
          }
          pos = resourceName.indexOf('/', pos + 1);
          if (pos < 0) { // flowname/streamname
            destination = resourceName;
          }
        }
      }
    }
    if (destination == null) {
      LOG.debug("Received a request with invalid path " + path);
      respondError(message.getChannel(), HttpResponseStatus.NOT_FOUND);
      return;
    }

    switch(operation) {
      case ENQUEUE: {
        // build a new event from the request
        EventBuilder builder = new EventBuilder();
        // set some built-in headers
        builder.setHeader(Constants.HEADER_FROM_COLLECTOR, this.collector.getName());
        builder.setHeader(Constants.HEADER_DESTINATION_STREAM, destination);
        // and transfer all other headers that are to be preserved
        String prefix = destination + ".";
        Set<String> headers = request.getHeaderNames();
        for (String header : headers) {
          String preservedHeader = isPreservedHeader(prefix, header);
          if (preservedHeader != null) {
            builder.setHeader(preservedHeader, request.getHeader(header));
          }
        }
        // read the body of the request and add it to the event
        ChannelBuffer content = request.getContent();
        int length = content.readableBytes();
        if (length > 0) {
          byte[] bytes = new byte[length];
          content.readBytes(bytes);
          builder.setBody(bytes);
        }
        Event event = builder.create();

        LOG.debug("Sending event to consumer: " + event);
        // let the consumer process the event.
        // in case of exception, respond with internal error
        try {
          this.collector.getConsumer().consumeEvent(event);
        } catch (Exception e) {
          LOG.error("Error consuming single event: " + e.getMessage());
          respondError(message.getChannel(), HttpResponseStatus.INTERNAL_SERVER_ERROR);
          return;
        }
        // all good - respond success
        respondSuccess(message.getChannel(), request);
        break;
      }
      case META: {
        LOG.debug("Received a request for stream meta data, which is not implemented yet.");
        respondError(message.getChannel(), HttpResponseStatus.NOT_IMPLEMENTED);
        return;
      }
      // GET means client wants to view the content of a queue.
      // 1. obtain a consumerId with GET stream?q=newConsumer
      // 2. dequeue an event with GET stream?q=dequeue with the consumerId as an HTTP header
      case NEWID: {
        String queueURI = FlowStream.buildStreamURI(destination).toString();
        QueueAdmin.GetGroupID op = new QueueAdmin.GetGroupID(queueURI.getBytes());
        long id = this.collector.getExecutor().execute(op);
        byte[] responseBody = Long.toString(id).getBytes();
        Map<String, String> headers = Maps.newHashMap();
        headers.put(Constants.HEADER_STREAM_CONSUMER, Long.toString(id));
        respondSuccess(message.getChannel(), request, HttpResponseStatus.CREATED, headers, responseBody);
        break;
      }
      case DEQUEUE: {
        // there must be a header with the consumer id in the request
        String idHeader = request.getHeader(Constants.HEADER_STREAM_CONSUMER);
        Long id = null;
        if (idHeader == null) {
          LOG.debug("Received a dequeue request without header " + Constants.HEADER_STREAM_CONSUMER);
        } else {
          try {
            id = Long.valueOf(idHeader);
          } catch (NumberFormatException e) {
            LOG.debug("Received a dequeue request with a invalid header "
                + Constants.HEADER_STREAM_CONSUMER + ": " + e.getMessage());
        } }
        if (null == id) {
          respondError(message.getChannel(), HttpResponseStatus.BAD_REQUEST);
          return;
        }
        // valid consumer id, dequeue and return it
        String queueURI = FlowStream.buildStreamURI(destination).toString();
        QueueDequeue dequeue = new QueueDequeue(
            queueURI.getBytes(),
            new QueueConsumer(0, id, 1),
            new QueueConfig(new QueuePartitioner.RandomPartitioner(), false)); // false means we don't need to ack
        DequeueResult result = this.collector.getExecutor().execute(dequeue);
        if (result.isFailure()) {
          LOG.error("Error dequeueing from stream " + queueURI + ": " + result.getMsg());
          respondError(message.getChannel(), HttpResponseStatus.INTERNAL_SERVER_ERROR);
          return;
        }
        if (result.isEmpty()) {
          respondSuccess(message.getChannel(), request, HttpResponseStatus.NO_CONTENT);
          return;
        }
        // try to deserialize into an event (tuple)
        Map<String, String> headers;
        byte[] body;
        try {
          TupleSerializer serializer = new TupleSerializer(false);
          Tuple tuple = serializer.deserialize(result.getValue());
          headers = tuple.get("headers");
          body = tuple.get("body");
        } catch (Exception e) {
          LOG.error("Exception when deserializing data from stream "
              + queueURI + " into an event: " + e.getMessage());
          respondError(message.getChannel(), HttpResponseStatus.INTERNAL_SERVER_ERROR);
          return;
        }
        // prefix each header with the destination to distinguish them from HTTP headers
        Map<String, String> prefixedHeaders = Maps.newHashMap();
        for (Map.Entry<String, String> header : headers.entrySet())
            prefixedHeaders.put(destination + "." + header.getKey(), header.getValue());
        // now the headers and body are ready to be sent back
        respondSuccess(message.getChannel(), request, HttpResponseStatus.OK, prefixedHeaders, body);
        break;
      }
      default: {
        // this should not happen because we already checked above -> internal error
        respondError(message.getChannel(), HttpResponseStatus.INTERNAL_SERVER_ERROR);
      }
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e)
      throws Exception {
    LOG.error("Exception caught for collector '" + this.collector.getName() + "'. ", e.getCause());
    e.getChannel().close();
  }
}
