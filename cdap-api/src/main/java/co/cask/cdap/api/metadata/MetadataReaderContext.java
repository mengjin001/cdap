/*
 * Copyright © 2018 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package co.cask.cdap.api.metadata;

import java.util.Map;

/**
 * The context for reading metadata from program
 */
public interface MetadataReaderContext {

  /**
   * Returns a Map of {@link MetadataScope} to {@link MetadataRecord} representing all metadata (including properties
   * and tags) for the specified {@link MetadataEntity} in both {@link MetadataScope#USER} and
   * {@link MetadataScope#SYSTEM}.
   */
  Map<MetadataScope, MetadataRecord> getMetadata(MetadataEntity metadataEntity);

  /**
   * Returns a {@link MetadataRecord} representing all metadata (including properties and tags) for the specified
   * {@link MetadataEntity} in the specified {@link MetadataScope}.
   */
  MetadataRecord getMetadata(MetadataScope scope, MetadataEntity metadataEntity);
}