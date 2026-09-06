# Keep Firebase model constructors and properties available for reflection.
-keepclassmembers class com.cropcast.app.data.model.** {
    public <init>();
    <fields>;
}
