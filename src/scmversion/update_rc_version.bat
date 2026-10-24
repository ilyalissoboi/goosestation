@echo off

rem --long always gives MAJOR.MINOR-COMMITS-gHASH, such as 0.1-47-g1234abc for the 47th commit after the annotated
rem 0.1 tag, and 0.1-0-g1234abc on the tagged commit itself.
SET "TAG="
FOR /F "tokens=* USEBACKQ" %%g IN (`git describe --long 2^>nul`) do (SET "TAG=%%g")

rem Without a release tag, the fields keep their 1.0.0.1 placeholder: an empty TAG would write an invalid VERSIONINFO.
echo %TAG%| findstr /R "^[0-9][0-9]*\.[0-9][0-9]*-[0-9][0-9]*-g" >nul || (
  echo No release tag found, keeping the placeholder version fields.
  exit /b 0
)

FOR /F "tokens=1-3 delims=.-" %%a IN ("%TAG%") do (
  SET "MAJOR=%%a"
  SET "MINOR=%%b"
  SET "COMMITS=%%c"
)

echo Setting the version fields to %MAJOR%.%MINOR%.%COMMITS% from %TAG%.
powershell -Command "(gc ..\duckstation-qt\duckstation-qt.rc) -replace '1,0,0,1', '%MAJOR%,%MINOR%,%COMMITS%,0' | Out-File -encoding ASCII ..\duckstation-qt\duckstation-qt.rc"
powershell -Command "(gc ..\duckstation-qt\duckstation-qt.rc) -replace '1\.0\.0\.1', '%MAJOR%.%MINOR%.%COMMITS%' | Out-File -encoding ASCII ..\duckstation-qt\duckstation-qt.rc"
