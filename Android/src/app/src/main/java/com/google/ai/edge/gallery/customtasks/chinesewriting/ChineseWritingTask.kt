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

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.litertlm.Contents
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

/**
 * A simple kids' handwriting-learning task: the user draws a single Chinese character on a canvas,
 * and the on-device vision LLM identifies the character, its pinyin, and its meaning.
 */
class ChineseWritingTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = BuiltInTaskId.LLM_CHINESE_WRITING,
      label = "Chinese Writing",
      category = Category.LLM,
      icon = Icons.Outlined.Edit,
      models = mutableListOf(),
      description =
        "Draw a Chinese character on the canvas and let the on-device AI tell you what it is",
      shortDescription = "Draw & identify Chinese characters",
      docUrl = "https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/README.md",
      sourceCodeUrl =
        "https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt",
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    model.runtimeHelper.initialize(
      context = context,
      model = model,
      taskId = task.id,
      supportImage = true,
      supportAudio = false,
      onDone = onDone,
      coroutineScope = coroutineScope,
      systemInstruction = systemInstruction,
    )
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    model.runtimeHelper.cleanUp(model = model, onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskData
    val viewModel: ChineseWritingViewModel = hiltViewModel()
    ChineseWritingScreen(
      modelManagerViewModel = myData.modelManagerViewModel,
      viewModel = viewModel,
      bottomPadding = myData.bottomPadding,
    )
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object ChineseWritingTaskModule {
  @Provides
  @IntoSet
  fun provideTask(): CustomTask {
    return ChineseWritingTask()
  }
}
