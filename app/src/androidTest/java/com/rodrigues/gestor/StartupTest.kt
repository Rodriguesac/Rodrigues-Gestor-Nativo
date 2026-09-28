package com.rodrigues.gestor
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
@RunWith(AndroidJUnit4::class)
class StartupTest {
 @Test fun bundledReactOpensLoginWithoutWhiteScreen(){
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  context.getSharedPreferences("rodrigues_gestor_secure",0).edit().clear().commit()
  ActivityScenario.launch(MainActivity::class.java).use { scenario ->
   var rendered=false
   for(attempt in 0..30){
    val latch=CountDownLatch(1)
    scenario.onActivity { activity ->
     val field=MainActivity::class.java.getDeclaredField("webView");field.isAccessible=true
     val web=field.get(activity) as? WebView
     if(web==null) latch.countDown() else web.evaluateJavascript("document.body.innerText.includes('PIN de acesso') && !!document.querySelector('form')") { result -> rendered=result=="true";latch.countDown() }
    }
    latch.await(2,TimeUnit.SECONDS)
    if(rendered)break
    Thread.sleep(500)
   }
   assertTrue("Bundled React login must render in the Android WebView",rendered)
  }
 }
}
