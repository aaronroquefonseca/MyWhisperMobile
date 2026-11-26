#!/usr/bin/env bash
set -euo pipefail

sdk_dir=${1:?Pass the Robolectric SDK directory}
mkdir -p "$sdk_dir"

fetch_sdk() (
    set -e
    local version=$1 digest=$2
    local jar="android-all-instrumented-$version.jar"
    local artifact="org/robolectric/android-all-instrumented/$version/$jar"
    local destination="$sdk_dir/$jar"

    verify() {
        printf '%s  %s\n' "$digest" "$1" | sha256sum --check --status
    }

    if [[ -f $destination ]] && verify "$destination"; then
        printf 'Verified cached %s\n' "$jar"
        return
    fi

    local staged
    staged=$(mktemp "$sdk_dir/.robolectric.XXXXXX")
    trap 'rm -f -- "$staged"' EXIT
    for repository in https://maven-central.storage-download.googleapis.com/maven2 https://repo.maven.apache.org/maven2; do
        if curl --fail --silent --show-error --location --retry 3 --retry-all-errors \
                --retry-max-time 25 --connect-timeout 10 --max-time 120 \
                --output "$staged" "$repository/$artifact" && verify "$staged"; then
            mv -f -- "$staged" "$destination"
            printf 'Downloaded and verified %s\n' "$jar"
            return
        fi
        printf 'Could not fetch a verified %s from %s\n' "$jar" "$repository" >&2
    done
    return 1
)

# Robolectric 4.14.1 uses API 29 and 33 for these tests, and API 35 for compile-time resources.
fetch_sdk 10-robolectric-5803371-i7 700fb3bb88ff346969b955241b86d2f55d395488bf5df1e8eddfbd003980c78a
fetch_sdk 13-robolectric-9030017-i7 1c3fd182c03d47626686ca3b39c4ec5bdb0898b1056f4eb8f96f32c6209a5c04
fetch_sdk 15-robolectric-12650502-i7 33c2d7cc4186d4605269d5d411fa1818972c5b30a9f70bfbbd3d430b076af52f
