#!/usr/bin/env bash
# ============================================================
#  用 PAT 直接 git push（完整保留本地提交历史）
#
#  作者：神仙道  1974147300
#
#  为什么不用 push-via-api.py：
#    API 那条路每次只能建一个 commit，会把本地积攒的几个提交压成一个。
#    内容和最终文件一样，但历史没了，以后 git pull 还得处理分叉。
#    PAT 走标准 git 协议，提交一个一个原样上去，什么都不动。
#
#  用法：
#    export GH_TOKEN=ghp_xxxxxxxx
#    bash scripts/push-with-token.sh              # 推到 origin 的 main
#    bash scripts/push-with-token.sh main         # 指定分支
#
#  token 权限：
#    classic PAT 勾 repo + workflow
#    （workflow 是必须的——本项目这次改到了 .github/workflows/build.yml，
#      少了它 git 会直接拒绝，报 "refusing to allow a Personal Access Token
#      to create or update workflow ... without `workflow` scope"）
#    fine-grained PAT 给 Contents:Read+Write、Workflows:Read+Write。
#
#  安全说明：
#    token 只出现在这一条命令的 URL 里，不写 .git/config、不改 remote。
#    脚本对输出做了脱敏，报错信息里不会带出 token。
# ============================================================
set -u

BRANCH="${1:-main}"

TOK="${GH_TOKEN:-${GITHUB_TOKEN:-}}"
if [ -z "$TOK" ]; then
    echo "缺少 GH_TOKEN。先 export GH_TOKEN=ghp_xxxx 再跑。" >&2
    echo "（别把 token 写进脚本里，也别提交到仓库）" >&2
    exit 2
fi

# 去掉空格和换行：从网页复制 token 经常带个尾随换行
TOK=$(printf '%s' "$TOK" | tr -d ' \r\n\t')

cd "$(dirname "$0")/.." || exit 1

URL=$(git config --get remote.origin.url 2>/dev/null)
if [ -z "$URL" ]; then
    echo "没有 origin 远程，先 git remote add origin <url>" >&2
    exit 1
fi

# 从各种写法的 remote 里抠出 owner/repo：
#   https://github.com/owner/repo.git
#   git@github.com:owner/repo.git
SLUG=$(printf '%s' "$URL" \
    | sed -e 's#^git@[^:]*:##' \
          -e 's#^https\{0,1\}://[^/]*/##' \
          -e 's#\.git$##' \
          -e 's#/*$##')
if [ -z "$SLUG" ]; then
    echo "解析不出 owner/repo（remote: $URL）" >&2
    exit 1
fi

echo "远程      : $SLUG"
echo "分支      : $BRANCH"
AHEAD=$(git rev-list --count "origin/$BRANCH..HEAD" 2>/dev/null || echo "?")
echo "待推提交  : $AHEAD"
git log --oneline "origin/$BRANCH..HEAD" 2>/dev/null | sed 's/^/  /'
echo

# GIT_TERMINAL_PROMPT=0：凭据不对时立刻失败，别卡在输入框上等
# 2>&1 收集起来再过滤，把 URL 里的 token 换成 ***
OUT=$(GIT_TERMINAL_PROMPT=0 git push \
    "https://x-access-token:${TOK}@github.com/${SLUG}.git" \
    "HEAD:${BRANCH}" 2>&1)
CODE=$?

printf '%s\n' "$OUT" | sed "s/${TOK}/***/g"

if [ $CODE -ne 0 ]; then
    echo
    echo "推送失败（exit $CODE）。常见原因：" >&2
    case "$OUT" in
        *"workflow"*scope*|*workflow*scope*)
            echo "  → token 少了 workflow 权限。改 token 勾上 workflow 再试" >&2 ;;
        *"Authentication failed"*|*"401"*)
            echo "  → token 无效或已过期（复制时别带空格）" >&2 ;;
        *"403"*|*"Permission"*)
            echo "  → token 的账号不是这个仓库的所有者/协作者" >&2 ;;
        *"non-fast-forward"*|*"rejected"*)
            echo "  → 远端有本地没有的提交，先 git pull --rebase" >&2 ;;
        *"not found"*|*"404"*)
            echo "  → 仓库名或账号不对，或者 token 没有 repo 权限（私有仓库）" >&2 ;;
        *)
            echo "  → 看上面那行 git 的原话" >&2 ;;
    esac
    exit $CODE
fi

echo
echo "=== 推送成功 ==="
echo "仓库：https://github.com/${SLUG}"
echo "提醒：这个 token 已经用完了，建议现在就去 GitHub 撤销它"
echo "      https://github.com/settings/tokens"
