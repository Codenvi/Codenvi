# Minification is off for v1 (Project.md keeps the stack small). If you turn it on:
# Retrofit, OkHttp, Moshi codegen and Room ship their own consumer rules.
-keep class app.codenvi.net.** { *; }
-keep class app.codenvi.crypto.** { *; }
