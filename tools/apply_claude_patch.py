#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional


FILE_BLOCK_PATTERN = re.compile(
    r"\[FILE\]\s+(?P<path>[^\n\r]+)\r?\n(?P<content>.*?)\r?\n\[/FILE\]",
    re.DOTALL,
)

COMMIT_BLOCK_PATTERN = re.compile(
    r"\[COMMIT\]\r?\n(?P<message>.*?)\r?\n\[/COMMIT\]",
    re.DOTALL,
)

SUMMARY_BLOCK_PATTERN = re.compile(
    r"\[SUMMARY\]\r?\n(?P<summary>.*?)\r?\n\[/SUMMARY\]",
    re.DOTALL,
)


@dataclass
class FilePatch:
    path: Path
    content: str


@dataclass
class ClaudePatch:
    files: List[FilePatch]
    commit_message: Optional[str]
    summary: Optional[str]


class PatchFormatError(Exception):
    pass


class CommandError(Exception):
    pass


def parse_patch(text: str) -> ClaudePatch:
    files: List[FilePatch] = []

    for match in FILE_BLOCK_PATTERN.finditer(text):
        raw_path = match.group("path").strip()
        content = match.group("content")

        if not raw_path:
            raise PatchFormatError("Encountered [FILE] block with empty path.")

        files.append(FilePatch(path=Path(raw_path), content=content))

    commit_match = COMMIT_BLOCK_PATTERN.search(text)
    commit_message = commit_match.group("message").strip() if commit_match else None

    summary_match = SUMMARY_BLOCK_PATTERN.search(text)
    summary = summary_match.group("summary").strip() if summary_match else None

    if not files:
        raise PatchFormatError("No [FILE] blocks found.")

    return ClaudePatch(
        files=files,
        commit_message=commit_message,
        summary=summary,
    )


def ensure_safe_relative_path(path: Path) -> None:
    if path.is_absolute():
        raise PatchFormatError(f"Absolute paths are not allowed: {path}")

    if ".." in path.parts:
        raise PatchFormatError(f"Parent path traversal is not allowed: {path}")

    if not path.parts:
        raise PatchFormatError("Empty path is not allowed.")


def run_command(
    args: list[str],
    repo_root: Path,
    dry_run: bool,
    capture_output: bool = False,
) -> Optional[subprocess.CompletedProcess[str]]:
    command_str = " ".join(args)
    print(f"{'[DRY-RUN] Would run' if dry_run else 'Running'}: {command_str}")

    if dry_run:
        return None

    result = subprocess.run(
        args,
        cwd=repo_root,
        text=True,
        capture_output=capture_output,
        check=False,
    )

    if result.returncode != 0:
        stderr = (result.stderr or "").strip()
        stdout = (result.stdout or "").strip()
        details = stderr or stdout or "No additional error output."
        raise CommandError(f"Command failed: {command_str}\n{details}")

    return result


def get_repo_root() -> Path:
    result = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise CommandError("Current directory is not inside a git repository.")
    return Path(result.stdout.strip())


def get_current_branch(repo_root: Path) -> str:
    result = subprocess.run(
        ["git", "branch", "--show-current"],
        cwd=repo_root,
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise CommandError("Could not determine current git branch.")

    branch = result.stdout.strip()
    if not branch:
        raise CommandError("Repository is in detached HEAD state.")

    return branch


def local_branch_exists(branch_name: str, repo_root: Path) -> bool:
    result = subprocess.run(
        ["git", "show-ref", "--verify", "--quiet", f"refs/heads/{branch_name}"],
        cwd=repo_root,
        check=False,
    )
    return result.returncode == 0


def sanitize_branch_component(text: str) -> str:
    text = text.lower().strip()
    text = re.sub(r"^[a-z]+(?:\([^)]+\))?:\s*", "", text)
    text = re.sub(r"[^a-z0-9]+", "-", text)
    text = re.sub(r"-{2,}", "-", text).strip("-")
    return text or "claude-change"


def switch_or_create_branch(branch_name: str, repo_root: Path, dry_run: bool) -> None:
    if local_branch_exists(branch_name, repo_root):
        run_command(["git", "checkout", branch_name], repo_root, dry_run)
    else:
        run_command(["git", "checkout", "-b", branch_name], repo_root, dry_run)


def write_files(repo_root: Path, files: List[FilePatch], dry_run: bool) -> None:
    for file_patch in files:
        ensure_safe_relative_path(file_patch.path)
        full_path = repo_root / file_patch.path

        print(f"{'[DRY-RUN] Would write' if dry_run else 'Writing'}: {file_patch.path}")

        if dry_run:
            continue

        full_path.parent.mkdir(parents=True, exist_ok=True)
        full_path.write_text(file_patch.content, encoding="utf-8")


def stage_files(files: List[FilePatch], repo_root: Path, dry_run: bool) -> None:
    paths = [str(f.path) for f in files]
    run_command(["git", "add", "--", *paths], repo_root, dry_run)


def has_staged_changes(repo_root: Path, dry_run: bool) -> bool:
    if dry_run:
        return True

    result = subprocess.run(
        ["git", "diff", "--cached", "--quiet"],
        cwd=repo_root,
        check=False,
    )
    return result.returncode == 1


def commit_changes(message: str, repo_root: Path, dry_run: bool) -> None:
    run_command(["git", "commit", "-m", message], repo_root, dry_run)


def push_branch(branch_name: str, repo_root: Path, dry_run: bool) -> None:
    run_command(["git", "push", "-u", "origin", branch_name], repo_root, dry_run)


def get_remote_url(repo_root: Path) -> Optional[str]:
    result = subprocess.run(
        ["git", "remote", "get-url", "origin"],
        cwd=repo_root,
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        return None
    return result.stdout.strip()


def github_compare_url(remote_url: Optional[str], branch_name: str) -> Optional[str]:
    if not remote_url:
        return None

    remote_url = remote_url.strip()

    https_match = re.match(r"^https://github\.com/([^/]+)/([^/]+?)(?:\.git)?$", remote_url)
    ssh_match = re.match(r"^git@github\.com:([^/]+)/([^/]+?)(?:\.git)?$", remote_url)

    match = https_match or ssh_match
    if not match:
        return None

    owner, repo = match.groups()
    return f"https://github.com/{owner}/{repo}/compare/{branch_name}?expand=1"


def read_input_text(input_file: Optional[Path], from_clipboard: bool) -> str:
    if from_clipboard:
        try:
            import pyperclip
        except ImportError as exc:
            raise PatchFormatError(
                "Clipboard mode requires pyperclip. Install it with: pip install pyperclip"
            ) from exc

        text = pyperclip.paste()
        if not text.strip():
            raise PatchFormatError("Clipboard is empty.")
        return text

    if input_file is None:
        raise PatchFormatError("Provide an input file or use --from-clipboard.")

    if not input_file.exists():
        raise PatchFormatError(f"Input file not found: {input_file}")

    return input_file.read_text(encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Apply Claude patch output to files, commit, and optionally push."
    )
    parser.add_argument(
        "input_file",
        nargs="?",
        type=Path,
        help="Path to Claude response text file.",
    )
    parser.add_argument(
        "--from-clipboard",
        action="store_true",
        help="Read Claude response from clipboard instead of a file.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Show what would happen without writing files or running git.",
    )
    parser.add_argument(
        "--no-commit",
        action="store_true",
        help="Write files and stage them, but do not commit.",
    )
    parser.add_argument(
        "--commit-message",
        type=str,
        default=None,
        help="Override the [COMMIT] block message.",
    )
    parser.add_argument(
        "--create-branch",
        action="store_true",
        help="Create or switch to a Claude branch before applying changes.",
    )
    parser.add_argument(
        "--branch-name",
        type=str,
        default=None,
        help="Explicit branch name to use with --create-branch.",
    )
    parser.add_argument(
        "--branch-prefix",
        type=str,
        default="claude/",
        help="Prefix used when auto-generating a branch name.",
    )
    parser.add_argument(
        "--push",
        action="store_true",
        help="Push branch to origin after commit.",
    )
    parser.add_argument(
        "--allow-main-push",
        action="store_true",
        help="Allow pushing protected branches like main/master.",
    )

    args = parser.parse_args()

    if args.from_clipboard and args.input_file is not None:
        print("Error: use either an input file or --from-clipboard, not both.", file=sys.stderr)
        return 1

    protected_branches = {"main", "master"}

    try:
        repo_root = get_repo_root()
        text = read_input_text(args.input_file, args.from_clipboard)
        patch = parse_patch(text)

        print(f"Repository root: {repo_root}")
        print(f"Parsed {len(patch.files)} file(s).")

        if patch.summary:
            print("\nSummary:")
            print(patch.summary)

        commit_message = args.commit_message or patch.commit_message
        if not args.no_commit and not commit_message:
            raise PatchFormatError(
                "No commit message found. Provide a [COMMIT] block or use --commit-message."
            )

        current_branch = get_current_branch(repo_root)
        print(f"Current branch: {current_branch}")

        if args.create_branch:
            if args.branch_name:
                target_branch = args.branch_name.strip()
            else:
                if not commit_message:
                    raise PatchFormatError(
                        "Cannot auto-generate branch name without a commit message."
                    )
                target_branch = f"{args.branch_prefix}{sanitize_branch_component(commit_message)}"

            if target_branch in protected_branches:
                raise PatchFormatError(f"Refusing to create protected branch: {target_branch}")

            switch_or_create_branch(target_branch, repo_root, args.dry_run)
            current_branch = target_branch
            print(f"Using branch: {current_branch}")

        write_files(repo_root, patch.files, args.dry_run)
        stage_files(patch.files, repo_root, args.dry_run)

        if not args.no_commit:
            if not has_staged_changes(repo_root, args.dry_run):
                raise PatchFormatError(
                    "No staged changes detected for the files in this patch. "
                    "The output may match the current file contents exactly."
                )
            commit_changes(commit_message, repo_root, args.dry_run)

        if args.push:
            if args.no_commit:
                raise PatchFormatError("Refusing to push because --no-commit was used.")

            if current_branch in protected_branches and not args.allow_main_push:
                raise PatchFormatError(
                    f"Refusing to push protected branch '{current_branch}'. "
                    "Use --allow-main-push only if you really mean it."
                )

            push_branch(current_branch, repo_root, args.dry_run)

            if not args.dry_run:
                compare_url = github_compare_url(get_remote_url(repo_root), current_branch)
                if compare_url:
                    print("\nGitHub compare URL:")
                    print(compare_url)

        print("\nDone.")
        return 0

    except (PatchFormatError, CommandError, OSError) as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())