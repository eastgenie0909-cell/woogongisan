package com.woogong.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.app.Activity

private val Day = lightColorScheme(
 primary=Color(0xff5146c9),onPrimary=Color.White,primaryContainer=Color(0xffeeebff),onPrimaryContainer=Color(0xff393073),
 background=Color(0xfff4f5f9),onBackground=Color(0xff202738),surface=Color.White,onSurface=Color(0xff202738),
 surfaceVariant=Color(0xffeaeaf2),onSurfaceVariant=Color(0xff687086),outlineVariant=Color(0xffe0e3ed))
private val Night = darkColorScheme(
 primary=Color(0xffb9afff),onPrimary=Color(0xff2a215c),primaryContainer=Color(0xff302a4d),onPrimaryContainer=Color(0xffe6e0ff),
 background=Color(0xff0d111a),onBackground=Color(0xffe5e8f1),surface=Color(0xff191f2c),onSurface=Color(0xffe5e8f1),
 surfaceVariant=Color(0xff252c3b),onSurfaceVariant=Color(0xffa5aec2),outlineVariant=Color(0xff30394b))

@Composable internal fun WoogongTheme(content:@Composable ()->Unit){
 val dark=isSystemInDarkTheme();val view=LocalView.current
 SideEffect{
  (view.context as? Activity)?.let{activity->
   WindowCompat.getInsetsController(activity.window,view).apply{
    isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark
   }
  }
 }
 MaterialTheme(colorScheme=if(dark)Night else Day,content=content)
}

internal val Ink:Color @Composable get()=MaterialTheme.colorScheme.onSurface
internal val Mint:Color @Composable get()=MaterialTheme.colorScheme.primary
internal val Gold:Color @Composable get()=if(isSystemInDarkTheme())Color(0xffe5bb70) else Color(0xff906824)
