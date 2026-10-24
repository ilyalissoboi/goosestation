@echo off

SET "TAG="
FOR /F "tokens=* USEBACKQ" %%g IN (`git describe --dirty 2^>nul`) do (SET "TAG=%%g")

rem The fields are filled from a release tag such as 0.1-7465. Without one (the repository has no tags yet),
rem they keep their 1.0.0.1 placeholder: an empty TAG would write an invalid VERSIONINFO.
echo %TAG%| findstr /R "^[0-9]\.[0-9]-[0-9]" >nul || (
  echo No release tag found, keeping the placeholder version fields.
  exit /b 0
)

powershell -Command "(gc ..\duckstation-qt\duckstation-qt.rc) -replace '1,0,0,1', '"%TAG:~0,1%","%TAG:~2,1%","%TAG:~4,4%",0' | Out-File -encoding ASCII ..\duckstation-qt\duckstation-qt.rc"
powershell -Command "(gc ..\duckstation-qt\duckstation-qt.rc) -replace '1.0.0.1', '"%TAG:~0,1%"."%TAG:~2,1%"."%TAG:~4,4%"' | Out-File -encoding ASCII ..\duckstation-qt\duckstation-qt.rc"
