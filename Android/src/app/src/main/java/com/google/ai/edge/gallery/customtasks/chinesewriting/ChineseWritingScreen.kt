/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.customtasks.chinesewriting

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageError
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageLoading
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

private const val OUTPUT_PX = 512

private const val IDENTIFY_PROMPT =
  "The image shows a single Chinese character (汉字) handwritten by a young child. " +
    "It is always exactly one real Chinese character, even if the strokes are messy, " +
    "shaky, uneven, or imperfect — young children have rough handwriting, so be generous " +
    "and judge by the overall stroke structure and shape, not neatness. " +
    "Identify the single most likely character. Always commit to your best single guess; " +
    "never ask a question and never say the drawing is unclear. " +
    "Reply in exactly this format and nothing else:\n" +
    "汉字: <the character>\n" +
    "Pinyin: <pinyin with tone marks>\n" +
    "Meaning: <short English meaning>"

/**
 * Renders the captured strokes into a clean white [OUTPUT_PX] square bitmap for the vision model.
 *
 * The drawing is cropped to its bounding box and centered so the character fills most of the frame
 * regardless of where or how small the child wrote it. No guide lines are drawn — the on-screen
 * grid is purely a visual aid and is intentionally excluded for cleaner recognition.
 */
private fun strokesToBitmap(strokes: List<List<Offset>>): Bitmap {
  val bmp = Bitmap.createBitmap(OUTPUT_PX, OUTPUT_PX, Bitmap.Config.ARGB_8888)
  val canvas = android.graphics.Canvas(bmp)
  canvas.drawColor(android.graphics.Color.WHITE)

  val points = strokes.flatten()
  if (points.isEmpty()) return bmp

  val minX = points.minOf { it.x }
  val minY = points.minOf { it.y }
  val maxX = points.maxOf { it.x }
  val maxY = points.maxOf { it.y }
  // Floor the box side so a single dot / thin line doesn't blow up the scale.
  val side = maxOf(maxX - minX, maxY - minY, 1f)
  // Content occupies ~80% of the frame, centered.
  val scale = OUTPUT_PX * 0.8f / side
  val centerX = (minX + maxX) / 2f
  val centerY = (minY + maxY) / 2f
  val tx = OUTPUT_PX / 2f - centerX * scale
  val ty = OUTPUT_PX / 2f - centerY * scale
  fun mapX(x: Float) = x * scale + tx
  fun mapY(y: Float) = y * scale + ty

  val paint =
    android.graphics.Paint().apply {
      color = android.graphics.Color.BLACK
      strokeWidth = OUTPUT_PX * 0.045f
      style = android.graphics.Paint.Style.STROKE
      strokeCap = android.graphics.Paint.Cap.ROUND
      strokeJoin = android.graphics.Paint.Join.ROUND
      isAntiAlias = true
    }
  for (stroke in strokes) {
    if (stroke.isEmpty()) continue
    if (stroke.size == 1) {
      canvas.drawPoint(mapX(stroke[0].x), mapY(stroke[0].y), paint)
      continue
    }
    val path = android.graphics.Path()
    path.moveTo(mapX(stroke[0].x), mapY(stroke[0].y))
    for (i in 1 until stroke.size) {
      path.lineTo(mapX(stroke[i].x), mapY(stroke[i].y))
    }
    canvas.drawPath(path, paint)
  }
  return bmp
}

@Composable
fun ChineseWritingScreen(
  modelManagerViewModel: ModelManagerViewModel,
  modifier: Modifier = Modifier,
  bottomPadding: Dp = 0.dp,
  viewModel: ChineseWritingViewModel = hiltViewModel(),
) {
  val context = LocalContext.current
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val model = modelManagerUiState.selectedModel
  val chatUiState by viewModel.uiState.collectAsState()

  // Each completed stroke is a list of points; the in-progress stroke is appended live.
  val strokes = remember { mutableStateListOf<List<Offset>>() }
  var currentStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }

  val modelReady = modelManagerUiState.isModelInitialized(model = model)
  val inProgress = chatUiState.inProgress
  val hasDrawing = strokes.isNotEmpty() || currentStroke.isNotEmpty()

  // Derive display state from the last significant message so that:
  //   • A fresh Identify request always shows the spinner first (ChatMessageLoading is last).
  //   • Once tokens start streaming in, the growing text is shown live.
  //   • A second Identify press correctly shows the spinner again, not the stale old reply.
  val lastMsg =
    chatUiState.messagesByModel[model.name]?.lastOrNull {
      it is ChatMessageLoading || it is ChatMessageText || it is ChatMessageError
    }
  val showSpinner = inProgress && (lastMsg == null || lastMsg is ChatMessageLoading)
  val displayText: String? =
    (lastMsg as? ChatMessageText)
      ?.takeIf { it.side == ChatSide.AGENT }
      ?.content
      ?.takeIf { it.isNotBlank() }

  Column(
    modifier =
      modifier
        .fillMaxSize()
        .padding(16.dp)
        .padding(bottom = bottomPadding),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      text = stringResource(R.string.chinese_writing_instruction),
      style = MaterialTheme.typography.titleMedium,
      textAlign = TextAlign.Center,
    )

    // Drawing canvas: white square with black ink.
    Box(
      modifier =
        Modifier.fillMaxWidth()
          .aspectRatio(1f)
          .clip(RoundedCornerShape(16.dp))
          .background(Color.White)
          .border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
    ) {
      Canvas(
        modifier =
          Modifier.fillMaxSize()
            .pointerInput(Unit) {
              detectDragGestures(
                onDragStart = { offset -> currentStroke = listOf(offset) },
                onDrag = { change, _ ->
                  currentStroke = currentStroke + change.position
                },
                onDragEnd = {
                  if (currentStroke.isNotEmpty()) {
                    strokes.add(currentStroke)
                    currentStroke = emptyList()
                  }
                },
                onDragCancel = {
                  if (currentStroke.isNotEmpty()) {
                    strokes.add(currentStroke)
                    currentStroke = emptyList()
                  }
                },
              )
            }
      ) {
        // 米字格 practice guide: faint dashed center cross + both diagonals (on-screen only).
        val gridColor = Color.LightGray.copy(alpha = 0.6f)
        val gridStroke = 1.dp.toPx()
        val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 12f), 0f)
        val w = size.width
        val h = size.height
        drawLine(gridColor, Offset(w / 2f, 0f), Offset(w / 2f, h), gridStroke, pathEffect = dash)
        drawLine(gridColor, Offset(0f, h / 2f), Offset(w, h / 2f), gridStroke, pathEffect = dash)
        drawLine(gridColor, Offset(0f, 0f), Offset(w, h), gridStroke, pathEffect = dash)
        drawLine(gridColor, Offset(w, 0f), Offset(0f, h), gridStroke, pathEffect = dash)

        val strokeWidthPx = size.width * 0.025f
        val allStrokes =
          if (currentStroke.isNotEmpty()) strokes + listOf(currentStroke) else strokes.toList()
        for (stroke in allStrokes) {
          for (i in 1 until stroke.size) {
            drawLine(
              color = Color.Black,
              start = stroke[i - 1],
              end = stroke[i],
              strokeWidth = strokeWidthPx,
              cap = StrokeCap.Round,
            )
          }
          if (stroke.size == 1) {
            drawCircle(color = Color.Black, radius = strokeWidthPx / 2f, center = stroke[0])
          }
        }
      }
    }

    // Action buttons.
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      OutlinedButton(
        modifier = Modifier.weight(1f),
        enabled = hasDrawing && !inProgress,
        onClick = {
          strokes.clear()
          currentStroke = emptyList()
          viewModel.clearAllMessages(model = model)
        },
      ) {
        Text(stringResource(R.string.chinese_writing_btn_clear))
      }
      Button(
        modifier = Modifier.weight(1f),
        enabled = hasDrawing && modelReady && !inProgress,
        onClick = {
          val task = modelManagerViewModel.getTaskById(BuiltInTaskId.LLM_CHINESE_WRITING)!!
          val bitmap = strokesToBitmap(strokes.toList())
          // Clear the previous result from the display so the UI starts fresh.
          // We intentionally do NOT reset the underlying conversation — resetSession
          // has a stale-callback race that lets old tokens bleed into the new result,
          // and engine KV-cache resets are unreliable across backends. The conversation
          // accumulates history but the model always responds to the latest turn
          // (identical to how Ask Image works, which gives correct results).
          viewModel.clearAllMessages(model = model)
          viewModel.generateResponse(
            model = model,
            input = IDENTIFY_PROMPT,
            images = listOf(bitmap),
            audioMessages = emptyList(),
            onError = { errorMessage ->
              viewModel.handleError(
                context = context,
                task = task,
                model = model,
                modelManagerViewModel = modelManagerViewModel,
                errorMessage = errorMessage,
              )
            },
            allowThinking = false,
          )
        },
      ) {
        Text(stringResource(R.string.chinese_writing_btn_identify))
      }
    }

    // Response area.
    Box(
      modifier =
        Modifier.fillMaxWidth()
          .weight(1f)
          .clip(RoundedCornerShape(16.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant)
          .padding(16.dp),
      contentAlignment = Alignment.Center,
    ) {
      when {
        !modelReady -> {
          // Model still loading — show spinner + label.
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            CircularProgressIndicator(modifier = Modifier.padding(4.dp), strokeWidth = 3.dp)
            Text(
              text = stringResource(R.string.chinese_writing_model_loading),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
            )
          }
        }
        showSpinner -> {
          // Waiting for the first tokens of a new inference request.
          CircularProgressIndicator(modifier = Modifier.padding(4.dp), strokeWidth = 3.dp)
        }
        displayText != null -> {
          // Show the agent's reply (streams in live as tokens arrive).
          Text(
            text = displayText,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        else -> {
          // Nothing drawn or identified yet.
          Text(
            text = stringResource(R.string.chinese_writing_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }
      }
    }
  }
}
