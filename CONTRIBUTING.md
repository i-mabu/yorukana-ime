# Contributing

This project follows a unified development workflow shared across the
maintainer's repositories (adopted from aegis-app/aegis-bot).

## Development workflow

1. **Open an issue first.** Every change starts with an issue (use the
   issue templates). Describe the purpose, the planned work, and the
   definition of done.
2. **Create a branch from `main`** named `<type>/<issue-number>-<slug>`,
   e.g. `feat/12-test-command`, `fix/9-summary-resend`, `docs/3-readme`.
3. **Implement and test.** Run `./build.sh` locally (unit tests plus the
   APK build with badging verification); both must pass.
4. **Open a pull request.** Fill in the PR template and link the issue
   with `Closes #<number>`.
5. **Merge with "Rebase and merge"** to keep the history of `main`
   linear, then delete the branch.

## Commit messages

Commit messages use **Conventional Commits with English subjects**:

```
feat: add master data counts display
fix: persist the posted date to stop re-sending after restarts
docs: update documentation for v1.0.0
```

Types in use: `feat`, `fix`, `docs`, `test`, `chore`, `refactor`.

## Releases

Releases are cut from `main` by the maintainer personally: bump the
version in the manifest, tag `vX.Y.Z`, and publish a GitHub Release with
notes only (no binaries are attached). Release builds are produced with
`./build.sh` on the maintainer's machine.

## Notes

- Never commit secrets.
- `libs/` and the tool jars under `tools/` are build inputs fetched by
  `tools/resolve.py` / `build-apk.sh`; they are git-ignored.
