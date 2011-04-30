/*
 * ConsoleWriteInputEvent.java
 *
 * Copyright (C) 2009-11 by RStudio, Inc.
 *
 * This program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */
package org.rstudio.studio.client.workbench.views.console.events;

import com.google.gwt.event.shared.GwtEvent;

public class ConsoleWriteInputEvent extends GwtEvent<ConsoleWriteInputHandler>
{
   public static final Type<ConsoleWriteInputHandler> TYPE = new Type<ConsoleWriteInputHandler>();

   public ConsoleWriteInputEvent(String input)
   {
      this.input_ = input;
   }

   public String getInput()
   {
      return input_;
   }

   private String input_;

   @Override
   public Type<ConsoleWriteInputHandler> getAssociatedType()
   {
      return TYPE;
   }

   @Override
   protected void dispatch(ConsoleWriteInputHandler handler)
   {
      handler.onConsoleWriteInput(this);
   }
}