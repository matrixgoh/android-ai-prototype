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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

private const val IDENTIFY_PROMPT =
  "This is a single handwritten Chinese character drawn by a child on a white canvas. " +
    "Please identify the Chinese character. Respond briefly with: " +
    "1) The Chinese character itself, 2) Its pinyin pronunciation, 3) Its English meaning. " +
    "Keep the response short and child-friendly. If the drawing is unclear or not a valid " +
    "character, gently say so and encourage them to try again."

/** Renders the captured strokes into a white-background bitmap suitable for the vision model. */
private fun strokesToBitmap(strokes: List<List<Offset>>, sizePx: Int): Bitmap {
  val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
  val canvas = android.graphics.Canvas(bmp)
  canvas.drawColor(android.graphics.Color.WHITE)
  val paint =
    android.graphics.Paint().apply {
      color = android.graphics.Color.BLACK
      strokeWidth = sizePx * 0.025f
      style = android.graphics.Paint.Style.STROKE
      strokeCap = android.graphics.Paint.Cap.ROUND
      strokeJoin = android.graphics.Paint.Join.ROUND
      isAntiAlias = true
    }
  for (stroke in strokes) {
    if (stroke.isEmpty()) continue
    if (stroke.size == 1) {
      // A single dot: draw a small point.
      canvas.drawPoint(stroke[0].x, stroke[0].y, paint)
      continue
    }
    val path = android.graphics.Path()
    path.moveTo(stroke[0].x, stroke[0].y)
    for (i in 1 until stroke.size) {
      path.lineTo(stroke[i].x, stroke[i].y)
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
  // Pixel size of the canvas, captured from layout for accurate bitmap rendering.
  var canvasSizePx by remember { mutableStateOf(0) }

  val modelReady = modelManagerUiState.isModelInitialized(model = model)
  val inProgress = chatUiState.inProgress
  val hasDrawing = strokes.isNotEmpty() || currentStroke.isNotEmpty()

  // Extract the latest agent text reply (if any) for display.
  val latestReply: String? =
    chatUiState.messagesByModel[model.name]
      ?.lastOrNull { it is ChatMessageText }
      ?.let { (it as ChatMessageText).content }
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
          .onSizeChanged { canvasSizePx = it.width }
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
        },
      ) {
        Text(stringResource(R.string.chinese_writing_btn_clear))
      }
      Button(
        modifier = Modifier.weight(1f),
        enabled = hasDrawing && modelReady && !inProgress,
        onClick = {
          val sizePx = if (canvasSizePx > 0) canvasSizePx else 1024
          val bitmap = strokesToBitmap(strokes.toList(), sizePx)
          viewModel.generateResponse(
            model = model,
            input = IDENTIFY_PROMPT,
            images = listOf(bitmap),
            audioMessages = emptyList(),
            onError = { errorMessage ->
              viewModel.handleError(
                context = context,
                task = modelManagerViewModel.getTaskById(BuiltInTaskId.LLM_CHINESE_WRITING)!!,
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
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            CircularProgressIndicator(
              modifier = Modifier.padding(4.dp),
              strokeWidth = 3.dp,
            )
            Text(
              text = stringResource(R.string.chinese_writing_model_loading),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
            )
          }
        }
        inProgress && latestReply.isNullOrBlank() -> {
          CircularProgressIndicator(modifier = Modifier.padding(4.dp), strokeWidth = 3.dp)
        }
        !latestReply.isNullOrBlank() -> {
          Text(
            text = latestReply,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        else -> {
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
