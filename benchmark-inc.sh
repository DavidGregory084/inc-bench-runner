#!/bin/bash
set -euxo pipefail

scriptDir=$(dirname $(readlink -f "$0"))
runnerDir="${scriptDir}/runner"
incRepoDir="${scriptDir}/inc-repo"
benchRepoDir="${scriptDir}/bench-repo"
benchmarkSha="HEAD"
incStartSha="HEAD"
incEndSha="HEAD"

runBenchmark() {
    java -jar "$runnerDir/out/runner/assembly/dest/out.jar" \
      --inc-repo-dir "$incRepoDir" \
      --benchmark-dir "$benchRepoDir/bench"
}

runBenchmarkForRevRange() {
    git clone https://github.com/DavidGregory084/inc.git "$incRepoDir"

    if [ "$incStartSha" == "$incEndSha" ]; then
        (cd "$incRepoDir" && git reset --hard "$incStartSha" && "${scriptDir}/mill" -i --disable-ticker "main.assembly")
        runBenchmark
    else
        (cd "$incRepoDir" && git rev-list --first-parent --reverse "${incStartSha}^..${incEndSha}") | while read -r rev; do
            (cd "$incRepoDir" && git reset --hard "$rev" && "${scriptDir}/mill" -i --disable-ticker "main.assembly")
            runBenchmark
        done
    fi
}

while [[ $# -gt 0 ]]; do
    key="$1"

    case $key in
        -b|--benchmark-sha)
        benchmarkSha="$2"
        shift # past argument
        shift # past value
        ;;
        -i|--inc-start-sha)
        incStartSha="$2"
        shift # past argument
        shift # past value
        ;;
        -i|--inc-end-sha)
        incEndSha="$2"
        shift # past argument
        shift # past value
        ;;
        *)    # unknown option
        shift # past argument
        shift
        ;;
    esac
done

(cd "$runnerDir" && "${scriptDir}/mill" -i --disable-ticker "runner.assembly")

git clone https://github.com/DavidGregory084/inc.git "$benchRepoDir"
(cd "$benchRepoDir" && git reset --hard "$benchmarkSha")

runBenchmarkForRevRange

rm -rf "$incRepoDir"
rm -rf "$benchRepoDir"
