@echo off
echo ````````````````````````
echo Starting IginX
echo ````````````````````````

if "%OS%" == "Windows_NT" setlocal

pushd %~dp0..
if NOT DEFINED IGINX_HOME set IGINX_HOME=%CD%
popd

set PATH="%JAVA_HOME%\bin\";%PATH%
set "FULL_VERSION="
set "MAJOR_VERSION="
set "MINOR_VERSION="

for /f tokens^=2-5^ delims^=.-_+^" %%j in ('java -fullversion 2^>^&1') do (
	set "FULL_VERSION=%%j-%%k-%%l-%%m"
	IF "%%j" == "1" (
	    set "MAJOR_VERSION=%%k"
	    set "MINOR_VERSION=%%l"
	) else (
	    set "MAJOR_VERSION=%%j"
	    set "MINOR_VERSION=%%k"
	)
)

set JAVA_VERSION=%MAJOR_VERSION%

@REM we do not check jdk that version less than 1.6 because they are too stale...
IF "%JAVA_VERSION%" == "6" (
		echo IginX only supports jdk >= 8, please check your java version.
		goto finally
)
IF "%JAVA_VERSION%" == "7" (
		echo IginX only supports jdk >= 8, please check your java version.
		goto finally
)

if "%OS%" == "Windows_NT" setlocal

set IGINX_CONF=%IGINX_HOME%\conf\config.properties

@setlocal ENABLEDELAYEDEXPANSION ENABLEEXTENSIONS
set is_conf_path=false
for %%i in (%*) do (
	IF "%%i" == "-c" (
		set is_conf_path=true
	) ELSE IF "!is_conf_path!" == "true" (
		set is_conf_path=false
		set IGINX_CONF=%%i
	) ELSE (
		set CONF_PARAMS=!CONF_PARAMS! %%i
	)
)

if NOT DEFINED MAIN_CLASS set MAIN_CLASS=cn.edu.tsinghua.iginx.Iginx
if NOT DEFINED JAVA_HOME goto :err


@REM -----------------------------------------------------------------------------
@REM Compute Memory for JVM configurations

if ["%system_cpu_cores%"] LSS ["1"] set system_cpu_cores="1"

set liner=0
for /f  %%b in ('wmic ComputerSystem get TotalPhysicalMemory') do (
	set /a liner+=1
	if !liner!==2 set system_memory=%%b
)

echo wsh.echo FormatNumber(cdbl(%system_memory%)/(1024*1024), 0) > %temp%\tmp.vbs
for /f "tokens=*" %%a in ('cscript //nologo %temp%\tmp.vbs') do set system_memory_in_mb=%%a
del %temp%\tmp.vbs
set system_memory_in_mb=%system_memory_in_mb:,=%

set /a half_=%system_memory_in_mb%/2
set /a quarter_=%system_memory_in_mb%/4

if ["%half_%"] GTR ["1024"] set half_=1024
if ["%quarter_%"] GTR ["8192"] set quarter_=8192

if ["%half_%"] GTR ["%quarter_%"] (
	set max_heap_size_in_mb=%half_%
) else set max_heap_size_in_mb=%quarter_%

set MAX_HEAP_SIZE=%max_heap_size_in_mb%M

@REM -----------------------------------------------------------------------------
@REM JVM Opts we'll use in legacy run or installation
set JAVA_OPTS=-ea^
 -DIGINX_HOME=%IGINX_HOME%^
 -DIGINX_DRIVER=%IGINX_HOME%\driver^
 -DIGINX_CONF=%IGINX_CONF%

set HEAP_OPTS=-Xmx%MAX_HEAP_SIZE% -Xms%MAX_HEAP_SIZE% -Xloggc:"%IGINX_HOME%\gc.log" -XX:+PrintGCDateStamps -XX:+PrintGCDetails

@REM ***** CLASSPATH library setting *****
@REM Ensure that any user defined CLASSPATH variables are not used on startup
set CLASSPATH="%IGINX_HOME%\lib\*"
goto okClasspath

@REM -----------------------------------------------------------------------------
:okClasspath

@REM set DRIVER=
@REM setx DRIVER "%IGINX_HOME%\driver"

"%JAVA_HOME%\bin\java" %JAVA_OPTS% %HEAP_OPTS% -cp %CLASSPATH% %MAIN_CLASS%

@REM reg delete "HKEY_CURRENT_USER\Environment" /v "DRIVER" /f
@REM set DRIVER=

goto finally

:err
echo JAVA_HOME environment variable must be set!
pause


@REM -----------------------------------------------------------------------------
:finally

pause

ENDLOCAL
