/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.wm.shell.freeform

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.android.wm.shell.sysui.ShellCommandHandler
import java.io.PrintWriter

/** Handles shell commands for FreeformController. */
class FreeformShellCommandHandler(
    private val controller: FreeformController,
    private val context: Context
) : ShellCommandHandler.ShellCommandActionHandler {

    override fun onShellCommand(args: Array<out String>, pw: PrintWriter): Boolean {
        if (args.isEmpty()) {
            pw.println("Invalid command")
            return false
        }
        return when (args[0]) {
            "startTask" -> runStartTask(args, pw)
            "startIntent" -> runStartIntent(args, pw)
            "exitFreeform" -> runExitFreeform(args, pw)
            else -> {
                pw.println("Invalid command: " + args[0])
                false
            }
        }
    }

    private fun runStartTask(args: Array<out String>, pw: PrintWriter): Boolean {
        if (args.size < 2) {
            // First argument is the action name.
            pw.println("Error: task id should be provided as argument.")
            return false
        }
        val taskId = args[1].toInt()
        controller.startTask(taskId, null)
        return true
    }

    private fun runStartIntent(args: Array<out String>, pw: PrintWriter): Boolean {
        if (args.size < 2) {
            // First argument is the action name.
            pw.println("Error: component should be provided as argument.")
            return false
        }
        val component = ComponentName.unflattenFromString(args[1])
        val intent =
            PendingIntent.getActivity(
                context,
                0,
                Intent().apply {
                    setComponent(component)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    )
                },
                PendingIntent.FLAG_IMMUTABLE
            )
        controller.startIntent(intent, null)
        return true
    }

    private fun runExitFreeform(args: Array<out String>, pw: PrintWriter): Boolean {
        if (args.size < 2) {
            // First argument is the action name.
            pw.println("Error: task id should be provided as argument.")
            return false
        }
        val taskId = args[1].toInt()
        controller.exitFreeform(taskId)
        return true
    }

    override fun printShellCommandHelp(pw: PrintWriter, prefix: String) {
        pw.println("${prefix}startTask <taskId>")
        pw.println("$prefix  Start a running task in freeform mode")
        pw.println("$prefix    Example: startTask 10")
        pw.println("${prefix}startIntent <component>")
        pw.println("$prefix  Start a component in freeform mode")
        pw.println("$prefix    Example: startIntent com.example.app/.ExampleActivity")
        pw.println("${prefix}exitFreeform <taskId>")
        pw.println("$prefix  Exit freeform.")
        pw.println("$prefix    Example: exitFreeform 10")
    }
}
