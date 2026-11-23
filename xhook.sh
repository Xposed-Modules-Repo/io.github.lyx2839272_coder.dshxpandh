#!/bin/bash
# xhook -- LSPosed 模块配置工具（可移植版）
# 关键：App 数据在各自 mount namespace，必须 nsenter 写才生效
#   xhook add <关键词>        为 App 写外部配置（自动 nsenter）
#   xhook hook <包名> <目标>   追加 hook 目标
#   xhook show <包名>         看当前生效配置
#   xhook restart <包名>      重启 App 并报注入状态
#   xhook log [包名]          看模块日志
#   xhook targets            内置目标表
#   xhook list               已配外部配置的 App
set -u
# 模块源码目录：优先环境变量，其次脚本同级 ../xmod，最后本机默认
# 三种形态：环境变量 > 分发包（脚本同目录） > 源码目录（../xmod）
SD=$(cd "$(dirname "$0")" 2>/dev/null && pwd)
if [ -n "${XMOD:-}" ]; then X=$XMOD
elif [ -d "$SD/recipes" ]; then X=$SD
else X=$(cd "$SD/.." 2>/dev/null && pwd)/xmod; fi
pick_dir() { for d in "$@"; do [ -d "$d" ] && { echo "$d"; return; }; done; }
X=$(pick_dir "$X" "$SD" "$SD/../xmod" "$HOME/xmod" "/root/workspace/xmod")
[ -z "$X" ] && { echo 'module dir not found; set XMOD=<path>'; exit 1; }
P=$X/assets/profiles

# 可配路径（都有默认值，环境变量可覆盖）
TMPD=${XHOOK_TMP:-}
if [ -z "$TMPD" ]; then
  for d in /sdcard/DSH-Output/xhook "$SD/tmp" "${TMPDIR:-/tmp}/xhook"; do
    mkdir -p "$d" 2>/dev/null && [ -w "$d" ] && { TMPD=$d; break; }
  done
fi
[ -z "$TMPD" ] && { echo 'no writable temp dir; set XHOOK_TMP'; exit 1; }
APP_HOME=${XHOOK_HOME:-/data/user/0}
CFG=${XHOOK_CFG:-$TMPD/config.json}

# root 通道自动探测
R=
if command -v psh >/dev/null 2>&1 && psh 'id' 2>/dev/null | grep -q 'uid=0'; then R=psh
elif command -v su >/dev/null 2>&1 && su -c 'id' 2>/dev/null | grep -q 'uid=0'; then R=su
elif command -v rish >/dev/null 2>&1; then
  RID=$(rish -c id 2>/dev/null)
  case "$RID" in
    *uid=0*) R=rish-root ;;
    *) R=rish-shell ;;
  esac
elif command -v shizuku >/dev/null 2>&1; then R=shizuku
fi
[ -z "$R" ] && { echo 'need root (psh/su) or Shizuku (rish)'; exit 1; }

# 能力分级：root 才能写 App 私有目录 / 读 lspd 日志
case "$R" in
  psh|su|rish-root) CAP_NS=1; CAP_LOG=1 ;;
  *)               CAP_NS=0; CAP_LOG=0 ;;
esac

As() {
  case "$R" in
    psh)         psh "$1" ;;
    su)          su -c "$1" ;;
    rish|rish-root|rish-shell) rish -c "$1" ;;
    shizuku)     shizuku "$1" ;;
  esac
}

# 依赖探测（缺了不是不能用，只是部分功能退化）
HAS_PY=0; command -v python3 >/dev/null 2>&1 && HAS_PY=1
HAS_NS=0; command -v nsenter >/dev/null 2>&1 && HAS_NS=1
dep_check() {
  [ "$HAS_PY" = 0 ] && echo "  ! 无 python3：recipe/arg 的合并功能不可用，改用整文件覆盖"
  [ "$HAS_NS" = 0 ] && echo "  ! 无 nsenter：无法写进 App 私有目录，只能用 APK 内置配置"
}

TARGETS='cipher   javax.crypto.Cipher doFinal
md       java.security.MessageDigest digest
hmac     javax.crypto.Mac doFinal
sign     java.security.Signature sign
b64      android.util.Base64 encode
b64d     android.util.Base64 decode'

find_pid() { As "pidof $1 2>/dev/null" | cut -d' ' -f1 | tr -d '\r'; }

# 把文件写进 App 的 namespace（关键：nsenter + chown）
push_cfg() {
  pkg=$1; src=$2
  if [ "$CAP_NS" = 0 ]; then
    MINE=/sdcard/Download/xhook
    mkdir -p "$MINE" 2>/dev/null
    cp "$src" "$MINE/$pkg.json" 2>/dev/null || { echo "ERR: cannot write $MINE"; return 1; }
    echo "pushed -> $MINE/$pkg.json  (no-root: sdcard mode)"
    echo "NOTE: target app must hold MANAGE_EXTERNAL_STORAGE to read it"
    echo "  $R pm grant $pkg android.permission.MANAGE_EXTERNAL_STORAGE"
    return 0
  fi
  pid=$(find_pid "$pkg")
  if [ -z "$pid" ]; then echo "ERR: $pkg not running (start it first)"; return 1; fi
  root=$(As "nsenter -t $pid -m -- test -d /data/user/0/$pkg && echo /data/user/0 || echo /data/data" | tr -d '\r')
  [ -z "$root" ] && root=$APP_HOME
  if [ "$HAS_NS" = 0 ]; then echo "ERR: nsenter missing (busybox may provide it: busybox nsenter)"; return 1; fi
  uid=$(As "nsenter -t $pid -m -- stat -c %u $root/$pkg 2>/dev/null" | tr -d '\r')
  [ -z "$uid" ] && { echo "ERR: cannot stat $pkg data dir"; return 1; }
  As "nsenter -t $pid -m -- sh -c 'mkdir -p $root/$pkg/files'" >/dev/null 2>&1
  As "nsenter -t $pid -m -- sh -c 'cp $src $root/$pkg/files/xhook.json; chown $uid:$uid $root/$pkg/files/xhook.json; chmod 644 $root/$pkg/files/xhook.json'" 2>&1 | head -2
  echo "$pkg" >> "$TMPD/pushed.txt" 2>/dev/null
  sort -u "$TMPD/pushed.txt" -o "$TMPD/pushed.txt" 2>/dev/null
  echo "pushed -> $pkg (uid=$uid)"
}

pull_cfg() {
  pkg=$1
  pid=$(find_pid "$pkg")
  [ -z "$pid" ] && return 1
  As "nsenter -t $pid -m -- cat ${root:-$APP_HOME}/$pkg/files/xhook.json 2>/dev/null"
}

cmd_targets() { echo "$TARGETS"; }

# 从日志判断某 App 当前 pid 是否真的被注入（maps 判据在本机无效）
inject_pid() {
  p=$1
  LOG=$(As 'for d in /data/adb/lspd/log /data/adb/lspd/logs /data/adb/lsp/log /data/misc/lspd/log; do l=$(ls -t $d/modules*.log 2>/dev/null | head -1); [ -n "$l" ] && { echo $l; break; }; done' | tr -d '\r')
  [ -z "$LOG" ] && return
  As "grep '($p)\[' $LOG 2>/dev/null | tail -1" | tr -d '\r' | awk '{gsub(/:/,"",$4); print $4}'
}

cmd_list() {
  echo '包名                            运行  注入  外部配置'
  echo '------------------------------------------------------------------'
  pkgs=${XHOOK_PKGS:-$(cat "$TMPD/pushed.txt" 2>/dev/null | tr '\n' ' ')}
  [ -z "$pkgs" ] && { echo '(no tracked apps; run: xhook recipe apply <name> <pkg>)'; return; }
  for p in $pkgs; do
    pid=$(find_pid "$p")
    if [ -z "$pid" ]; then st='no '; inj='-  '; sz='';
    else
      st='yes'
      lp=$(inject_pid "$p")
      if [ "$lp" = "$pid" ]; then inj='yes'; else inj='no '; fi
      sz=$(As "nsenter -t $pid -m -- sh -c 'stat -c %s /data/user/0/$p/files/xhook.json 2>/dev/null || stat -c %s /data/data/$p/files/xhook.json 2>/dev/null'" | tr -d '\r')
    fi
    printf '%-32s %-5s %-5s %s\n' "$p" "$st" "$inj" "${sz:-none}"
  done
  echo ''
  echo '注入=no 但运行=yes：重启该 App；Chrome 类有独立 zygote，注不进。'
}
cmd_add() {
  [ $# -ge 1 ] || { echo 'need keyword'; exit 1; }
  lst=$(As "/system/bin/pm list packages | grep -i '$1' | sed 's/package://' | head -3" | tr -d '\r')
  [ -z "$lst" ] && { echo "no match: $1"; exit 1; }
  pkg=$(echo "$lst" | head -1)
  echo "pick: $pkg"
  tmp=$TMPD/_push.json
  if [ -f "$P/$pkg.json" ]; then cp "$P/$pkg.json" "$tmp"; echo 'using builtin profile for this package';
  else cp "$P/default.json" "$tmp"; echo 'using default profile'; fi
  push_cfg "$pkg" "$tmp" || exit 1
  rm -f "$tmp"
  echo "NEXT: xhook restart $pkg"
}

cmd_hook() {
  [ $# -ge 3 ] || { echo 'need: pkg <target|class> [method]'; exit 1; }
  pkg=$1
  pid=$(find_pid "$pkg"); [ -z "$pid" ] && { echo "$pkg not running"; exit 1; }
  tmp=$TMPD/_t.json
  pull_cfg "$pkg" > "$tmp" 2>/dev/null
  [ -s "$tmp" ] || { echo "no existing config; run xhook add $pkg first"; rm -f "$tmp"; exit 1; }
  if [ $# -ge 4 ]; then cls=$3; mtd=$4; else
    r=$(echo "$TARGETS" | while read -r n c m; do [ "$n" = "$2" ] && { echo "$c|$m"; break; }; done)
    cls=${r%%|*}; mtd=${r##*|}
  fi
  [ -z "$cls" ] && { echo "unknown target: $2"; rm -f "$tmp"; exit 1; }
  python3 - "$tmp" "$cls" "$mtd" <<'PY'
import json, sys
p, cls, mtd = sys.argv[1], sys.argv[2], sys.argv[3]
d = json.load(open(p))
h = d.setdefault('hooks', [])
if any(x.get('class') == cls and x.get('method') == mtd for x in h):
    print('already: ' + cls + '.' + mtd)
else:
    h.append({'class': cls, 'method': mtd})
    json.dump(d, open(p, 'w'), indent=2)
    print('added: ' + cls + '.' + mtd)
PY
  push_cfg "$pkg" "$tmp"
  rm -f "$tmp"
  echo "NEXT: xhook restart $pkg"
}

cmd_show() {
  [ $# -ge 1 ] || { echo "need pkg"; exit 1; }
  pid=$(find_pid "$1")
  if [ -z "$pid" ]; then
    echo "$1 is not running (nsenter needs a live pid)"
    echo "start it first, then re-run: xhook show $1"
    return 1
  fi
  out=$(pull_cfg "$1")
  if [ -z "$out" ]; then
    echo "(no config on device; falls back to builtin #own / #def)"
    return 0
  fi
  printf "%s\n" "$out" | head -30
}

cmd_arg() {
  [ $# -ge 5 ] || { echo 'need: pkg class method idx value'; exit 1; }
  pkg=$1; cls=$2; mtd=$3; idx=$4; val=$5
  pid=$(find_pid "$pkg"); [ -z "$pid" ] && { echo "$pkg not running"; exit 1; }
  tmp=$TMPD/_a.json
  pull_cfg "$pkg" > "$tmp" 2>/dev/null
  [ -s "$tmp" ] || { echo "no config; run xhook add $pkg"; rm -f "$tmp"; exit 1; }
  python3 - "$tmp" "$cls" "$mtd" "$idx" "$val" <<'PY'
import json, sys
p, cls, mtd, idx, val = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]), sys.argv[5]
d = json.load(open(p))
h = d.setdefault('hooks', [])
for x in h:
    if x.get('class') == cls and x.get('method') == mtd:
        x['mode'] = 'args'
        st = x.setdefault('set', [])
        for it in st:
            if it.get('i') == idx: it['v'] = val; break
        else: st.append({'i': idx, 'v': val})
        json.dump(d, open(p, 'w'), indent=2)
        print('updated: ' + cls + '.' + mtd + ' arg[' + str(idx) + '] -> ' + val)
        break
else:
    h.append({'class': cls, 'method': mtd, 'mode': 'args', 'set': [{'i': idx, 'v': val}]})
    json.dump(d, open(p, 'w'), indent=2)
    print('added: ' + cls + '.' + mtd + ' arg[' + str(idx) + '] -> ' + val)
PY
  push_cfg "$pkg" "$tmp"
  rm -f "$tmp"
  echo "NEXT: xhook restart $pkg"
}

cmd_recipe() {
  sub=${1:-list}; shift 2>/dev/null || true
  case "$sub" in
    list)
      echo '可用配方（xhook recipe apply <名> <包名>）'
      echo '----------------------------------------------------------------'
      for f in $X/recipes/*.json; do
        [ -f "$f" ] || continue
        n=$(basename "$f" .json)
        d=$(python3 -c "import json;d=json.load(open('$f'));h=d.get('hooks',[]);a=d.get('actions',[]);print(str(len(h))+' hooks'+((' +'+str(len(a))+' act') if a else ''))" 2>/dev/null)
        printf '%-24s %s\n' "$n" "$d"
      done
      ;;
    show)
      [ -n "${1:-}" ] || { echo 'need recipe name'; exit 1; }
      cat "$X/recipes/$1.json" 2>/dev/null || echo "no such recipe: $1"
      ;;
    apply)
      [ $# -ge 2 ] || { echo 'need: recipe pkg|keyword'; exit 1; }
      rec=$1; target=$2
      src="$X/recipes/$rec.json"
      [ -f "$src" ] || { echo "no recipe: $rec"; exit 1; }
      # 目标既可是包名也可是关键词
      case "$target" in
        *.*.*) pkg=$target ;;
        *) pkg=$(As "/system/bin/pm list packages | grep -i '$target' | sed 's/package://' | head -1" | tr -d '\r') ;;
      esac
      [ -z "$pkg" ] && { echo "package not found: $target"; exit 1; }
      if [ "$HAS_PY" = 0 ]; then
        echo "need python3 for merge; falling back to plain overwrite"
        pid=$(find_pid "$pkg"); [ -z "$pid" ] && { echo "$pkg not running"; exit 1; }
        push_cfg "$pkg" "$src" && { echo "NEXT: xhook restart $pkg"; exit 0; }
        exit 1
      fi
      echo "recipe=$rec pkg=$pkg"
      pid=$(find_pid "$pkg"); [ -z "$pid" ] && { echo "$pkg not running (start it first)"; exit 1; }
      cur=$TMPD/_cur.json
      pull_cfg "$pkg" > "$cur" 2>/dev/null
      python3 - "$src" "$cur" "$pkg" <<'PY'
import json, sys, os
src, cur, pkg = sys.argv[1], sys.argv[2], sys.argv[3]
rec = json.load(open(src))
if os.path.getsize(cur) > 2:
    try: base = json.load(open(cur))
    except Exception: base = None
else:
    base = None
if not base:
    base = {'process': rec.get('process','main'), 'sample': rec.get('sample',3),
            'window_ms': rec.get('window_ms',1000), 'head': rec.get('head',200),
            'tail': rec.get('tail',200), 'max_logs': rec.get('max_logs',400),
            'hooks': [], 'actions': []}
def sig(x): return (x.get('class'), x.get('method'), tuple(x.get('classes') or []), x.get('mode'))
have = {sig(h) for h in base.get('hooks', [])}
added = 0
for h in rec.get('hooks', []):
    if sig(h) not in have:
        base.setdefault('hooks', []).append(h); have.add(sig(h)); added += 1
for a in rec.get('actions', []):
    if a not in base.setdefault('actions', []):
        base['actions'].append(a); added += 1
for k in ('process','sample','window_ms','head','tail','max_logs'):
    if k in rec and k not in base: base[k] = rec[k]
json.dump(base, open(cur, 'w'), indent=2)
print('merged %d new entries (total hooks=%d)' % (added, len(base.get('hooks', []))))
PY
      rc=$?
      [ $rc -ne 0 ] && { rm -f "$cur"; exit 1; }
      push_cfg "$pkg" "$cur"
      rm -f "$cur"
      echo "NEXT: xhook restart $pkg"
      ;;
    *) echo 'recipe list | show <name> | apply <name> <pkg|keyword>' ;;
  esac
}


cmd_build() {
  cd $X || exit 1
  vc=$(grep -o 'versionCode="[0-9]*"' AndroidManifest.xml | head -1 | grep -o '[0-9]*')
  nv=$((vc + 1))
  sed -i "s/versionCode=\"$vc\"/versionCode=\"$nv\"/" AndroidManifest.xml
  echo "versionCode $vc -> $nv"
  ENTRY=com.dsh.xmod.HookEntry sh build.sh 2>&1 | grep -E 'version sync|✅'
  STG=${XHOOK_STAGE:-/data/local/tmp}
  As "cp $X/mod_signed.apk $STG/_b.apk; chmod 644 $STG/_b.apk; /system/bin/pm install -r $STG/_b.apk" 2>&1 | tail -1
  As "rm -f $STG/_b.apk" >/dev/null 2>&1
  echo 'installed. restart target app to apply'
}

cmd_restart() {
  [ $# -ge 1 ] || { echo 'need pkg'; exit 1; }
  pkg=$1
  As "/system/bin/am force-stop $pkg" >/dev/null 2>&1
  act=$(As "/system/bin/cmd package resolve-activity --brief $pkg 2>/dev/null | tail -1" | tr -d '\r')
  if [ -n "$act" ]; then As "/system/bin/am start -n $act" >/dev/null 2>&1;
  else As "/system/bin/monkey -p $pkg -c android.intent.category.LAUNCHER 1" >/dev/null 2>&1; fi
  echo "restarted $pkg"
}

cmd_log() {
  if [ "$CAP_LOG" = 0 ]; then
    if [ $# -ge 1 ]; then As "logcat -d 2>/dev/null | grep core | grep \"$1\" | tail -10"
    else As "logcat -d 2>/dev/null | grep core | tail -10"; fi
    return
  fi
  LOG=$(As 'ls -t /data/adb/lspd/log/modules*.log 2>/dev/null | head -1' | tr -d '\r')
  [ -z "$LOG" ] && { echo '(no log)'; return; }
  if [ $# -ge 1 ]; then As "grep 'core \\[$1\\]' $LOG | tail -10"; else As "grep 'core \\[' $LOG | tail -10"; fi
}

case "${1:-}" in
  list)    cmd_list ;;
  add)     shift; cmd_add "$@" ;;
  hook)    shift; cmd_hook "$@" ;;
  show)    shift; cmd_show "$@" ;;
  restart) shift; cmd_restart "$@" ;;
  log)     shift; cmd_log "$@" ;;
  deps)    echo "channel=$R python3=$HAS_PY nsenter=$HAS_NS appdir=$CAP_NS lspdlog=$CAP_LOG"; dep_check ;;
  targets) cmd_targets ;;
  arg)     shift; cmd_arg "$@" ;;
  recipe)  shift; cmd_recipe "$@" ;;
  build)   cmd_build ;;
  -h|--help|help) sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//' ;;
  *) echo 'xhook list|add <kw>|hook|arg|recipe list|apply|show|show <pkg>|build|restart|log|targets|deps|-h' ;;
esac