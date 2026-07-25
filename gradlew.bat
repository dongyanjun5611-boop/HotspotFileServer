@echo off
setlocal

set "APP_HOME=%~dp0"
set "CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar"

if defined JAVA_HOME (
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA_EXE=java.exe"
)

"%JAVA_EXE%" -version >NUL 2>&1
if errorlevel 1 (
    echo Java was not found. Set JAVA_HOME to a JDK 17 installation. 1>&2
    exit /b 1
)

"%JAVA_EXE%" "-Dorg.gradle.appname=gradlew" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%

