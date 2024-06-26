/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.fs.azurebfs.services;

/**
 * A builder for AbfsClientContext class with different options to select and
 * build from.
 */
public class AbfsClientContextBuilder {

  private ExponentialRetryPolicy exponentialRetryPolicy;
  private StaticRetryPolicy staticRetryPolicy;
  private AbfsPerfTracker abfsPerfTracker;
  private AbfsCounters abfsCounters;

  public AbfsClientContextBuilder withExponentialRetryPolicy(
      final ExponentialRetryPolicy exponentialRetryPolicy) {
    this.exponentialRetryPolicy = exponentialRetryPolicy;
    return this;
  }

  public AbfsClientContextBuilder withStaticRetryPolicy(
      final StaticRetryPolicy staticRetryPolicy) {
    this.staticRetryPolicy = staticRetryPolicy;
    return this;
  }

  public AbfsClientContextBuilder withAbfsPerfTracker(
      final AbfsPerfTracker abfsPerfTracker) {
    this.abfsPerfTracker = abfsPerfTracker;
    return this;
  }

  public AbfsClientContextBuilder withAbfsCounters(final AbfsCounters abfsCounters) {
    this.abfsCounters = abfsCounters;
    return this;
  }

  /**
   * Build the context and get the instance with the properties selected.
   *
   * @return an instance of AbfsClientContext.
   */
  public AbfsClientContext build() {
    //validate the values
    return new AbfsClientContext(
        exponentialRetryPolicy,
        staticRetryPolicy,
        abfsPerfTracker,
        abfsCounters);
  }
}
