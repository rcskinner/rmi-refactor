# Scripts

Store repeatable development and maintenance scripts here. Scripts should be
safe to run from the repository root and document required tools and inputs.

`check-file-size.sh` rejects Java source files over 1,200 lines. Override the
limit for local evaluation with `MAX_FILE_LINES=<limit>`.

Run the maintenance checks from WSL Bash with:

```shell
bash ./scripts/check-todo-references.sh
bash ./scripts/check-file-size.sh
```

From WSL Bash, install the pre-commit hooks with
`bash ./scripts/setup-pre-commit.sh`. The hooks require Python, pip, Maven, and Git.
CI runs the same hooks across all tracked files before Maven verification.
