# HP Travail - durcissement release
# R8 obfusque et réduit automatiquement le code applicatif.

# Conserver les composants Android instanciés par le framework.
-keep class com.amaury.pointage.PointageApplication { *; }
-keep class com.amaury.pointage.MainActivity { *; }
-keep class com.amaury.pointage.SalaryActivity { *; }
-keep class com.amaury.pointage.DriveFolderPickerActivity { *; }
-keep class com.amaury.pointage.BackgroundPickerActivity { *; }
-keep class com.amaury.pointage.PdfPreviewActivity { *; }
-keep class com.amaury.pointage.PointageWidgetProvider { *; }
-keep class com.amaury.pointage.GeofenceBroadcastReceiver { *; }
-keep class com.amaury.pointage.BootReceiver { *; }

# Conserver les constructeurs XML des vues personnalisées.
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Réduire les informations utiles au reverse engineering dans les traces.
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*
