@echo off
REM sys_msvc.bat - the hosted system runtime under MSVC: cl /std:c++20 /W4 /WX
REM /EHsc /permissive- on sys_test.cxx and kira/os.cxx (its Win32 half).
REM
REM     kira\cpp\tests\sys_msvc.bat    build and run sys_test, the check-format
REM                                    diff (printf reference against
REM                                    kira::test::Suite) and the exit mode
REM
REM Visual Studio is found by bibo's tools\find_vs.bat, which is only called, never
REM copied. Outputs go to build\cpp-tests\sys_msvc under the repository root.
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

set "OUT=%ROOT%\build\cpp-tests\sys_msvc"
if not exist "%OUT%" mkdir "%OUT%"
set "TEST=%ROOT%\kira\cpp\tests\sys_test.cxx"
set "OSCXX=%ROOT%\kira\cpp\kira\os.cxx"
set /a FAILS=0

REM A stale sys_test.exe must never pass for a fresh one.
if exist "%OUT%\sys_test.exe" del /q "%OUT%\sys_test.exe"
%KCL% "%TEST%" "%OSCXX%" /Fo"%OUT%\\" /Fe"%OUT%\sys_test.exe" /link ws2_32.lib > "%OUT%\build.log" 2>&1
if errorlevel 1 (
    type "%OUT%\build.log"
    echo FAIL  sys_test does not build under MSVC
    exit /b 1
)
if not exist "%OUT%\sys_test.exe" (
    echo FAIL  cl reported success and wrote no sys_test.exe
    exit /b 1
)
echo ok    sys_test builds under MSVC /W4 /WX

"%OUT%\sys_test.exe" > "%OUT%\sys_test.out" 2> "%OUT%\sys_test.err"
set "RC=!errorlevel!"
type "%OUT%\sys_test.err"
findstr /r /c:"^[0-9]* checks, 0 failed" "%OUT%\sys_test.out" > nul
if "!RC!"=="0" if not errorlevel 1 (
    for /f "delims=" %%l in ('findstr /r /c:"^[0-9]* checks, 0 failed" "%OUT%\sys_test.out"') do echo ok    sys_test: %%l
    goto :format
)
findstr /b /c:"  FAIL" "%OUT%\sys_test.out"
echo FAIL  sys_test exited !RC!
set /a FAILS+=1

:format
REM The check format: bibo's printf code and kira::test::Suite print the same
REM bytes for the same script, and both exit 1 because the script has failures.
"%OUT%\sys_test.exe" testfmt-ref > "%OUT%\ref.out" 2> nul
set "RREF=!errorlevel!"
"%OUT%\sys_test.exe" testfmt-suite > "%OUT%\suite.out" 2> nul
set "RSUITE=!errorlevel!"
if "!RREF!"=="1" if "!RSUITE!"=="1" (
    echo ok    the check-format script exits 1 through printf and through Suite
    goto :cmp
)
echo FAIL  the check-format script exited !RREF! ^(printf^) and !RSUITE! ^(Suite^), not 1 and 1
set /a FAILS+=1

:cmp
fc /b "%OUT%\ref.out" "%OUT%\suite.out" > "%OUT%\fc.log" 2>&1
if errorlevel 1 (
    type "%OUT%\fc.log"
    echo FAIL  kira::test::Suite's output differs from the printf reference
    set /a FAILS+=1
) else (
    for %%s in ("%OUT%\ref.out") do if %%~zs equ 0 (
        echo FAIL  the check-format reference printed nothing
        set /a FAILS+=1
    ) else (
        echo ok    kira::test::Suite prints byte for byte what bibo's printf code prints
    )
)

"%OUT%\sys_test.exe" exit 5 > "%OUT%\exit.out" 2> nul
set "XRC=!errorlevel!"
findstr /x /c:"exiting" "%OUT%\exit.out" > nul
if "!XRC!"=="5" if not errorlevel 1 (
    echo ok    kira::os::exit^(5^) flushes stdout and exits 5
    goto :done
)
echo FAIL  the exit mode exited !XRC!
set /a FAILS+=1

:done
if !FAILS! neq 0 (
    echo.
    echo sys_msvc: !FAILS! failed
    exit /b 1
)
echo.
echo sys_msvc: all passed
exit /b 0
