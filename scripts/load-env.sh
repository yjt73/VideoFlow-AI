#!/usr/bin/env bash
# Source this file to export .env values without executing them as shell code.
load_videoflow_env() {
  local env_file="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/.env}"
  local env_line='' config_name config_value
  if [[ ! -f "$env_file" ]]; then
    printf 'Missing environment file: %s\n' "$env_file" >&2
    return 1
  fi
  while IFS= read -r env_line || [[ -n "$env_line" ]]; do
    env_line="${env_line%$'\r'}"
    env_line="${env_line#$'\xef\xbb\xbf'}"
    if [[ "$env_line" =~ ^[[:space:]]*([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]]; then
      config_name="${BASH_REMATCH[1]}"
      config_value="${BASH_REMATCH[2]}"
      config_value="${config_value#"${config_value%%[![:space:]]*}"}"
      config_value="${config_value%"${config_value##*[![:space:]]}"}"
      if [[ ${#config_value} -ge 2 ]]; then
        if [[ ( "${config_value:0:1}" == '"' && "${config_value: -1}" == '"' ) ||
              ( "${config_value:0:1}" == "'" && "${config_value: -1}" == "'" ) ]]; then
          config_value="${config_value:1:${#config_value}-2}"
        fi
      fi
      export "$config_name=$config_value"
    fi
  done < "$env_file"
}
load_videoflow_env "$@"
