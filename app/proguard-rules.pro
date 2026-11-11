# Mapsforge loads render themes / symbols by name and uses some desktop-only classes.
-keep class org.mapsforge.** { *; }
-dontwarn org.mapsforge.**
-keep class com.caverock.androidsvg.** { *; }
-dontwarn com.caverock.androidsvg.**

# BRouter loads its path model by class name (RoutingContext); keep the engine intact.
-keep class btools.** { *; }
-dontwarn btools.**

# Eclipse Paho MQTT finds its TCP/SSL network modules through ServiceLoader (META-INF/services).
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-dontwarn org.eclipse.paho.client.mqttv3.**
