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

package org.apache.tajo.master;

import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.hadoop.yarn.service.AbstractService;
import org.apache.tajo.master.event.TaskRequestEvent;
import org.apache.tajo.master.event.TaskSchedulerEvent;


public abstract class AbstractTaskScheduler extends AbstractService implements EventHandler<TaskSchedulerEvent> {

  protected int hostLocalAssigned;
  protected int rackLocalAssigned;
  protected int totalAssigned;

  /**
   * Construct the service.
   *
   * @param name service name
   */
  public AbstractTaskScheduler(String name) {
    super(name);
  }

  public int getHostLocalAssigned() {
    return hostLocalAssigned;
  }

  public int getRackLocalAssigned() {
    return rackLocalAssigned;
  }

  public int getTotalAssigned() {
    return totalAssigned;
  }

  public abstract void handleTaskRequestEvent(TaskRequestEvent event);
  public abstract int remainingScheduledObjectNum();
}
