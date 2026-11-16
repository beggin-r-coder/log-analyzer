#!/usr/bin/env bash
# Builds LogAnalyzer-eclipse-offline.zip: the source as a plain Eclipse project with every library in lib/, so it
# can be imported and run in Eclipse without Maven or internet access (corporate networks often block Maven Central).
# Usage: scripts/eclipse-offline/build.sh <output-dir>      (run from the repository root; needs a JDK and ./mvnw)
set -euo pipefail
OUT="$(mkdir -p "${1:-dist}" && cd "${1:-dist}" && pwd)"
MVN="${MVN:-./mvnw}"
VERSION="$($MVN -q -B -ntp help:evaluate -Dexpression=project.version -DforceStdout)"
WORK="$(mktemp -d)"
P="$WORK/log-analyzer"
mkdir -p "$P"

$MVN -q -B -ntp dependency:copy-dependencies -DincludeScope=runtime -DexcludeArtifactIds=spring-boot-devtools -DoutputDirectory="$P/lib"
git archive HEAD src/main README.md packaging | tar -x -C "$P"
sed -i "s/@project.version@/$VERSION/" "$P/src/main/resources/application.properties"
cp scripts/eclipse-offline/HOW-TO-RUN-IN-ECLIPSE.txt "$P/"

cat > "$P/.project" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<projectDescription>
	<name>log-analyzer</name>
	<comment>Log Analyzer (offline Eclipse project: all libraries are in lib/, no Maven needed)</comment>
	<projects></projects>
	<buildSpec>
		<buildCommand><name>org.eclipse.jdt.core.javabuilder</name><arguments></arguments></buildCommand>
	</buildSpec>
	<natures><nature>org.eclipse.jdt.core.javanature</nature></natures>
</projectDescription>
EOF
{
	echo '<?xml version="1.0" encoding="UTF-8"?>'
	echo '<classpath>'
	echo '	<classpathentry kind="src" path="src/main/java"/>'
	echo '	<classpathentry kind="src" path="src/main/resources" excluding="**/*.java"/>'
	echo '	<classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/JavaSE-17"/>'
	(cd "$P" && for j in lib/*.jar; do echo "	<classpathentry kind=\"lib\" path=\"$j\"/>"; done)
	echo '	<classpathentry kind="output" path="bin"/>'
	echo '</classpath>'
} > "$P/.classpath"
mkdir -p "$P/.settings"
printf '%s\n' 'eclipse.preferences.version=1' 'org.eclipse.jdt.core.compiler.codegen.targetPlatform=17' 'org.eclipse.jdt.core.compiler.compliance=17' \
	'org.eclipse.jdt.core.compiler.source=17' 'org.eclipse.jdt.core.compiler.release=enabled' 'org.eclipse.jdt.core.compiler.codegen.methodParameters=generate' \
	> "$P/.settings/org.eclipse.jdt.core.prefs"
printf '%s\n' 'eclipse.preferences.version=1' 'encoding/<project>=UTF-8' > "$P/.settings/org.eclipse.core.resources.prefs"

launch() { # title, program arguments
	cat > "$P/$1.launch" <<EOF
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<launchConfiguration type="org.eclipse.jdt.launching.localJavaApplication">
    <listAttribute key="org.eclipse.debug.core.MAPPED_RESOURCE_PATHS"><listEntry value="/log-analyzer"/></listAttribute>
    <listAttribute key="org.eclipse.debug.core.MAPPED_RESOURCE_TYPES"><listEntry value="4"/></listAttribute>
    <listAttribute key="org.eclipse.debug.ui.favoriteGroups"><listEntry value="org.eclipse.debug.ui.launchGroup.run"/><listEntry value="org.eclipse.debug.ui.launchGroup.debug"/></listAttribute>
    <stringAttribute key="org.eclipse.jdt.launching.MAIN_TYPE" value="com.anishka.log_analyzer.LogAnalyzerApplication"/>
    <stringAttribute key="org.eclipse.jdt.launching.PROGRAM_ARGUMENTS" value="$2"/>
    <stringAttribute key="org.eclipse.jdt.launching.PROJECT_ATTR" value="log-analyzer"/>
    <stringAttribute key="org.eclipse.jdt.launching.VM_ARGUMENTS" value="-Dfile.encoding=UTF-8"/>
</launchConfiguration>
EOF
}
launch "Log Analyzer - desktop app" "--desktop"
launch "Log Analyzer - web (localhost 8080)" ""

# check it compiles with nothing but the bundled libraries, as Eclipse will
mkdir -p "$WORK/check"
(cd "$P" && javac --release 17 -parameters -encoding UTF-8 -nowarn -cp "lib/*" -d "$WORK/check" $(find src/main/java -name '*.java'))
test -f "$WORK/check/com/anishka/log_analyzer/LogAnalyzerApplication.class"

rm -f "$OUT/LogAnalyzer-eclipse-offline.zip"
(cd "$WORK" && zip -qr "$OUT/LogAnalyzer-eclipse-offline.zip" log-analyzer)
rm -rf "$WORK"
ls -l "$OUT/LogAnalyzer-eclipse-offline.zip"
