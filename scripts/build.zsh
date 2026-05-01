#!/usr/bin/env zsh
set -euo pipefail

script_dir=${0:A:h}
project_root=${script_dir:h}
src_dir="${project_root}/src/main/java"
build_dir="${project_root}/build"
classes_dir="${build_dir}/classes"
dist_dir="${project_root}/dist"
jar_file="${dist_dir}/digiwize.jar"
main_class="com.digiwize.Digiwize"
app_version="1.0"
app_name="digiwize"

if ! command -v javac >/dev/null 2>&1; then
  print -u2 "javac was not found. Install a JDK and try again."
  exit 1
fi

if ! command -v jar >/dev/null 2>&1; then
  print -u2 "jar was not found. Install a JDK and try again."
  exit 1
fi

rm -rf "${build_dir}" "${dist_dir}"
mkdir -p "${classes_dir}" "${dist_dir}"

sources_file=$(mktemp)
trap 'rm -f "${sources_file}"' EXIT
find "${src_dir}" -name '*.java' -print | sort | while IFS= read -r source_file; do
  print -r -- "\"${source_file}\""
done > "${sources_file}"

if [[ ! -s "${sources_file}" ]]; then
  print -u2 "No Java source files found under ${src_dir}."
  exit 1
fi

print "Compiling digiwize..."
javac -encoding UTF-8 -d "${classes_dir}" @"${sources_file}"

print "Running self-test..."
java -Djava.awt.headless=true -cp "${classes_dir}" "${main_class}" --self-test

manifest_file="${build_dir}/manifest.mf"
cat > "${manifest_file}" <<MANIFEST
Implementation-Title: digiwize
Implementation-Version: ${app_version}
MANIFEST

print "Packaging ${jar_file}..."
jar --create --file "${jar_file}" --main-class "${main_class}" --manifest "${manifest_file}" -C "${classes_dir}" .

cat > "${dist_dir}/run-digiwize.zsh" <<'RUNNER'
#!/usr/bin/env zsh
set -euo pipefail
script_dir=${0:A:h}
java -jar "${script_dir}/digiwize.jar"
RUNNER
chmod +x "${dist_dir}/run-digiwize.zsh"

cat > "${dist_dir}/run-digiwize.ps1" <<'RUNNER'
$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
java -jar (Join-Path $scriptDir "digiwize.jar")
RUNNER

if [[ "$(uname -s)" == "Darwin" ]]; then
  app_bundle="${dist_dir}/${app_name}.app"
  app_contents="${app_bundle}/Contents"
  app_macos="${app_contents}/MacOS"
  app_resources="${app_contents}/Resources"
  app_java="${app_contents}/Java"
  icns_file="${app_resources}/${app_name}.icns"

  print "Packaging ${app_bundle}..."
  mkdir -p "${app_macos}" "${app_resources}" "${app_java}"
  cp "${jar_file}" "${app_java}/${app_name}.jar"

  java -Djava.awt.headless=true -cp "${classes_dir}" "${main_class}" --write-icns "${icns_file}"

  cat > "${app_contents}/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleDevelopmentRegion</key>
  <string>en</string>
  <key>CFBundleDisplayName</key>
  <string>digiwize</string>
  <key>CFBundleExecutable</key>
  <string>digiwize</string>
  <key>CFBundleIconFile</key>
  <string>digiwize.icns</string>
  <key>CFBundleIdentifier</key>
  <string>com.bpops.digiwize</string>
  <key>CFBundleInfoDictionaryVersion</key>
  <string>6.0</string>
  <key>CFBundleName</key>
  <string>digiwize</string>
  <key>CFBundlePackageType</key>
  <string>APPL</string>
  <key>CFBundleShortVersionString</key>
  <string>${app_version}</string>
  <key>CFBundleVersion</key>
  <string>${app_version}</string>
  <key>LSApplicationCategoryType</key>
  <string>public.app-category.utilities</string>
  <key>LSMinimumSystemVersion</key>
  <string>10.13</string>
  <key>NSHighResolutionCapable</key>
  <true/>
</dict>
</plist>
PLIST

  cat > "${app_macos}/${app_name}" <<'LAUNCHER'
#!/usr/bin/env zsh
set -euo pipefail
script_dir=${0:A:h}
app_root=${script_dir:h:h}
exec /usr/bin/java -jar "${app_root}/Contents/Java/digiwize.jar"
LAUNCHER
  chmod +x "${app_macos}/${app_name}"
fi

print "Built ${jar_file}"
print "Run with: java -jar ${jar_file}"
if [[ -d "${dist_dir}/${app_name}.app" ]]; then
  print "Built ${dist_dir}/${app_name}.app"
fi
