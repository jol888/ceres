package app.ceres.reader.web

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.ceres.reader.ui.CeresTheme
import app.ceres.reader.ui.InkButton
import app.ceres.reader.ui.Screen

/** Shows a captcha image for `java.getVerificationCode` and returns the typed text. */
class CaptchaActivity : ComponentActivity() {
    private var requestId: String? = null
    private var reported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestId = intent.getStringExtra(UiRequests.EXTRA_ID)
        val imageUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        val bitmap = intent.getByteArrayExtra(EXTRA_IMAGE)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        setContent {
            CeresTheme {
                var code by remember { mutableStateOf("") }
                Screen(title = "输入验证码", onBack = { finish() }) {
                    Column(
                        Modifier.fillMaxWidth().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap.asImageBitmap(), contentDescription = "验证码",
                                modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp),
                                contentScale = ContentScale.Fit, filterQuality = FilterQuality.None,
                            )
                        } else {
                            Text("验证码图片加载失败：$imageUrl")
                        }
                        OutlinedTextField(
                            value = code, onValueChange = { code = it }, singleLine = true,
                            label = { Text("验证码") }, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submit(code) }),
                        )
                        InkButton("确定", Modifier.fillMaxWidth(), enabled = code.isNotBlank()) { submit(code) }
                    }
                }
            }
        }
    }

    private fun submit(code: String) {
        report(code.trim())
        finish()
    }

    private fun report(code: String?) {
        if (reported) return
        reported = true
        UiRequests.complete(requestId, code)
    }

    override fun onDestroy() {
        if (isFinishing) report(null)
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_IMAGE = "image"

        fun intent(context: Context, imageUrl: String, image: ByteArray?, requestId: String) =
            Intent(context, CaptchaActivity::class.java)
                .putExtra(EXTRA_URL, imageUrl)
                .putExtra(EXTRA_IMAGE, image)
                .putExtra(UiRequests.EXTRA_ID, requestId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
