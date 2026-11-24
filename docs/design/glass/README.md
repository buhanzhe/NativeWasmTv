# 玻璃材质调整

播放器仅参考 [Tigris 的材质处理](https://github.com/lesnolie/Tigris/blob/3225098f1bd216a1135bba23878bf46c5a3672b0/app/src/main/java/com/leslie/tvhome/UpdateGlass.kt)，保留现有播放器布局、字体和交互。采用取样背景透色、中性轻罩层和细定向边缘；填充直接以抗锯齿圆角绘制，取消硬裁切和装饰性角部 PNG。无可取帧画面时保留清晰的深色半透明回退。

本目录的 image_gen PNG 为设计历史，已移出 APK。Bro 仍为 1.8.3 / versionCode 12。Tigris MIT 声明保留在应用 assets/licenses。
