#!/usr/bin/env bash
# Talks to the Should I Answer API the way LibPhoneNumberInfo's WebService does.
#
#   tools/sia-api.sh reviews +4930121212      community reviews for one number (JSON)
#   tools/sia-api.sh update 3976              the next database update after version 3976
set -euo pipefail

BASE=https://aapi.shouldianswer.net/srvapp

# what the app says about itself; versions from assets/sia/sia_info.dat and data_slice_info.dat
APP_FAMILY=SIA-NEXT
APP_VER=276
DB_VER=3976
COUNTRY=DE
DEVICE=dreamlte
MODEL=SM-G950F
MANUFACTURER=Samsung
API=26
OKHTTP=3.10.0

# a random id per session: 16 random bytes, base64, "/" -> "_", no "=" padding
APP_ID=$(head -c 16 /dev/urandom | base64 | tr '/' '_' | tr -d '=\n')

# Sends a multipart form with the common fields, the given extra ones, and _checksum:
# md5 over every value concatenated in the order Java's HashMap happens to iterate
# them, plus "saltandmira2". That order is fixed for these key names, so it is spelled
# out here rather than worked out.
call() {
  local path=$1; shift
  local -A f=(
    [_appId]=$APP_ID [_device]=$DEVICE [_model]=$MODEL [_manufacturer]=$MANUFACTURER
    [_api]=$API [_appFamily]=$APP_FAMILY [_appVer]=$APP_VER [_dbVer]=$DB_VER
    [_country]=$COUNTRY
  )
  local order=(_country _appVer _api _appId _model _manufacturer _dbVer _device _appFamily)

  # extra fields go first: "number" and "country" iterate before the rest
  local extra=()
  while [ $# -gt 0 ]; do f[$1]=$2; extra+=("$1"); shift 2; done
  order=("${extra[@]}" "${order[@]}")

  local joined="" args=()
  for k in "${order[@]}"; do
    joined+=${f[$k]}
    args+=(-F "$k=${f[$k]}")
  done
  local checksum
  checksum=$(printf '%s' "${joined}saltandmira2" | md5sum | cut -d' ' -f1)

  curl -sS -X POST -H "User-Agent: okhttp/$OKHTTP" "${args[@]}" \
    -F "_checksum=$checksum" "$BASE$path"
}

case ${1:-} in
  reviews)
    # the library drops the "+" and sends the country beside the number
    call /get-reviews number "${2#+}" country "$COUNTRY"
    ;;
  update)
    # gzip-compressed MTZD slice, or a short answer: NC (nothing new), OAP (app too
    # old: raise APP_VER), ODD (unknown version: start again from the base version)
    DB_VER=${2:-$DB_VER}
    call "/get-database/cached?_dbVer=$DB_VER" > update.bin
    if [ "$(head -c 2 update.bin | od -An -tx1 | tr -d ' ')" = 1f8b ]; then
      gunzip -c update.bin > update.dat
      echo "update.dat: $(head -c 4 update.dat) slice, $(wc -c < update.dat) bytes"
    else
      echo "answer: $(cat update.bin)"
    fi
    ;;
  *)
    sed -n '4,5p' "$0"; exit 1 ;;
esac
