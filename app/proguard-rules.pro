# Vosk's public fields and native method names are resolved by JNA.
-keep class org.vosk.LibVosk { *; }
-keep class org.vosk.Model { public <init>(...); }
-keep class org.vosk.Recognizer { public <init>(...); }
