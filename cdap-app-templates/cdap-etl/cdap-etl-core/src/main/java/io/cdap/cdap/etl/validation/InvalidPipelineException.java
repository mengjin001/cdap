/*
 * Copyright © 2019 Cask Data, Inc.
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
 *
 */

package io.cdap.cdap.etl.validation;

import io.cdap.cdap.etl.proto.v2.validation.ValidationError;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Thrown when a pipeline is invalid for some reason.
 */
public class InvalidPipelineException extends Exception {
  private final List<? extends ValidationError> errors;

  public InvalidPipelineException(ValidationError error) {
    super(error.getMessage());
    this.errors = Collections.singletonList(error);
  }

  public InvalidPipelineException(ValidationError error, Throwable cause) {
    super(error.getMessage(), cause);
    this.errors = Collections.singletonList(error);
  }

  public InvalidPipelineException(List<? extends ValidationError> errors) {
    super(errors.isEmpty() ? "" : errors.iterator().next().getMessage());
    this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
  }

  public InvalidPipelineException(List<? extends ValidationError> errors, Throwable cause) {
    super(cause.getMessage(), cause);
    this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
  }

  public List<? extends ValidationError> getErrors() {
    return errors;
  }
}
