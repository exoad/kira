@echo off
REM msvc.bat - the Kira C++ runtime under MSVC: cl /std:c++20 /W4 /WX /EHsc /permissive-.
REM
REM     kira\cpp\tests\msvc.bat              build and run rt_test, the must-not-compile
REM                                          cases, the panic modes and the exit-70 mode
REM     kira\cpp\tests\msvc.bat cl ARGS...   one cl with the flags above and the runtime
REM                                          on the include path (goldens.sh uses this)
REM
REM Visual Studio is found by bibo's tools\find_vs.bat, which is only called, never
REM copied. Outputs go to build\cpp-tests\msvc under the repository root.
setlocal EnableDelayedExpansion
for %%i in ("%~dp0..\..\..") do set "ROOT=%%~fi"
set "FINDVS=C:\Users\error\Code\bibo-kira\tools\find_vs.bat"
if not exist "%FINDVS%" (
    echo [error] %FINDVS% not found: no way to find Visual Studio
    exit /b 1
)
call "%FINDVS%" cl
if errorlevel 1 exit /b 1
set "KCL=cl /nologo /std:c++20 /W4 /WX /EHsc /permissive- /I "%ROOT%\kira\cpp""

if /i "%~1"=="cl" goto :passthrough

set "OUT=%ROOT%\build\cpp-tests\msvc"
if not exist "%OUT%" mkdir "%OUT%"
set "TEST=%ROOT%\kira\cpp\tests\rt_test.cxx"
set /a FAILS=0

REM A stale rt_test.exe must never pass for a fresh one.
if exist "%OUT%\rt_test.exe" del /q "%OUT%\rt_test.exe"
%KCL% "%TEST%" /Fo"%OUT%\\" /Fe"%OUT%\rt_test.exe" > "%OUT%\build.log" 2>&1
if errorlevel 1 (
    type "%OUT%\build.log"
    echo FAIL  rt_test does not build under MSVC
    exit /b 1
)
if not exist "%OUT%\rt_test.exe" (
    echo FAIL  cl reported success and wrote no rt_test.exe
    exit /b 1
)
echo ok    rt_test builds under MSVC /W4 /WX

"%OUT%\rt_test.exe" > "%OUT%\rt_test.out" 2> "%OUT%\rt_test.err"
set "RC=!errorlevel!"
type "%OUT%\rt_test.err"
findstr /r /c:"^[0-9]* checks, 0 failed" "%OUT%\rt_test.out" > nul
if "!RC!"=="0" if not errorlevel 1 (
    for /f "delims=" %%l in ('findstr /r /c:"^[0-9]* checks, 0 failed" "%OUT%\rt_test.out"') do echo ok    rt_test: %%l
    goto :negative
)
type "%OUT%\rt_test.out"
echo FAIL  rt_test exited !RC!
set /a FAILS+=1

:negative
REM A failing check inside a static_assert must be a compile error, and the
REM error must be the one the check raises: evaluation reaching kira::panic.
for %%n in (1 2 3 4 5 6) do (
    %KCL% /DKIRA_RT_NEGATIVE=%%n /c "%TEST%" /Fo"%OUT%\negative%%n.obj" > "%OUT%\negative%%n.log" 2>&1
    if errorlevel 1 (
        findstr /c:"kira::panic" "%OUT%\negative%%n.log" > nul
        if errorlevel 1 (
            type "%OUT%\negative%%n.log"
            echo FAIL  KIRA_RT_NEGATIVE=%%n failed to compile for another reason
            set /a FAILS+=1
        ) else (
            echo ok    KIRA_RT_NEGATIVE=%%n does not compile: its check reaches kira::panic
        )
    ) else (
        echo FAIL  KIRA_RT_NEGATIVE=%%n compiled: a failing check passed a static_assert
        set /a FAILS+=1
    )
)

REM Each runtime check, hit on purpose, aborts with "kira: ...".
for %%p in (div mod overflow shl shr index view slice list unwrap rc substring strat result assert) do (
    "%OUT%\rt_test.exe" panic %%p > "%OUT%\panic_%%p.out" 2> "%OUT%\panic_%%p.err"
    set "PRC=!errorlevel!"
    findstr /b /c:"kira: " "%OUT%\panic_%%p.err" > nul
    if errorlevel 1 (
        echo FAIL  panic %%p wrote no "kira: " line, exit !PRC!
        set /a FAILS+=1
    ) else if "!PRC!"=="0" (
        echo FAIL  panic %%p exited 0
        set /a FAILS+=1
    ) else if "!PRC!"=="3" (
        findstr /c:"did not panic" "%OUT%\panic_%%p.out" > nul
        if errorlevel 1 (
            for /f "delims=" %%l in ('findstr /b /c:"kira: " "%OUT%\panic_%%p.err"') do echo ok    panic %%p: %%l
        ) else (
            echo FAIL  panic %%p did not panic
            set /a FAILS+=1
        )
    ) else (
        for /f "delims=" %%l in ('findstr /b /c:"kira: " "%OUT%\panic_%%p.err"') do echo ok    panic %%p: %%l
    )
)

"%OUT%\rt_test.exe" throw > "%OUT%\throw.out" 2> "%OUT%\throw.err"
set "TRC=!errorlevel!"
findstr /x /c:"kira: boom" "%OUT%\throw.err" > nul
if "!TRC!"=="70" if not errorlevel 1 (
    echo ok    an uncaught kira::Error exits 70 with its message
    goto :done
)
echo FAIL  throw mode exited !TRC!
type "%OUT%\throw.err"
set /a FAILS+=1

:done
if !FAILS! neq 0 (
    echo.
    echo msvc: !FAILS! failed
    exit /b 1
)
echo.
echo msvc: all passed
exit /b 0

:passthrough
set "ARGS="
:collect
shift
if "%~1"=="" goto :runcl
set ARGS=!ARGS! %1
goto :collect
:runcl
%KCL% !ARGS!
exit /b !errorlevel!
