package com.example.diary.notification

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.diary.MainActivity
import com.example.diary.ui.theme.DiaryAppTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class AlarmInfo(val id: Long, val title: String, val content: String)

/**
 * 提醒触发时在锁屏之上显示的全屏提醒页：点亮屏幕，显示标题/内容，
 * 提供“关闭 / 稍后提醒 / 查看日记”。铃声和震动由 AlarmRingService 负责。
 */
class AlarmActivity : ComponentActivity() {

    private var info by mutableStateOf(AlarmInfo(-1L, "日记提醒", ""))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        info = readInfo(intent)

        setContent {
            DiaryAppTheme {
                AlarmScreen(
                    info = info,
                    onStop = { stopAndFinish() },
                    onSnooze = { snoozeAndFinish() },
                    onOpen = { openDiary() },
                    onTimeout = { finish() }
                )
            }
        }
    }

    // 已经显示着一个提醒页时又来了新提醒：刷新内容而不是再开一个页面
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        info = readInfo(intent)
    }

    @Suppress("DEPRECATION")
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun readInfo(i: Intent?): AlarmInfo = AlarmInfo(
        id = i?.getLongExtra(AlarmRingService.EXTRA_ENTRY_ID, -1L) ?: -1L,
        title = i?.getStringExtra(AlarmRingService.EXTRA_TITLE) ?: "日记提醒",
        content = i?.getStringExtra(AlarmRingService.EXTRA_CONTENT) ?: ""
    )

    private fun stopAndFinish() {
        AlarmRingService.stop(this)
        finish()
    }

    private fun snoozeAndFinish() {
        val cur = info
        AlarmRingService.snooze(this, cur.id, cur.title, cur.content)
        finish()
    }

    private fun openDiary() {
        val entryId = info.id
        val go = {
            AlarmRingService.stop(this)
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("entry_id", entryId)
                }
            )
            finish()
        }
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (km.isKeyguardLocked) {
            // 锁屏状态下先请求解锁，解锁成功再进入日记
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    go()
                }
            })
        } else {
            go()
        }
    }
}

@Composable
private fun AlarmScreen(
    info: AlarmInfo,
    onStop: () -> Unit,
    onSnooze: () -> Unit,
    onOpen: () -> Unit,
    onTimeout: () -> Unit
) {
    val timeText = remember(info) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) }

    // 响铃超时后服务会自己停止；页面也跟着退出，让屏幕可以正常熄灭
    LaunchedEffect(info) {
        delay(AlarmRingService.RING_TIMEOUT_MS + 5_000L)
        onTimeout()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Filled.Alarm, contentDescription = null, modifier = Modifier.size(64.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(timeText, fontSize = 56.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(24.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    info.title,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                if (info.content.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(info.content, fontSize = 18.sp, textAlign = TextAlign.Center)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) { Text("关闭", fontSize = 18.sp) }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onSnooze,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) { Text("稍后 ${AlarmRingService.SNOOZE_MINUTES} 分钟再提醒") }
            Spacer(modifier = Modifier.height(4.dp))
            TextButton(onClick = onOpen) { Text("查看日记") }
        }
    }
}
