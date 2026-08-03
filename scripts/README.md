# Scripts

Store repeatable development and maintenance scripts here. Scripts should be
safe to run from the repository root and document required tools and inputs.

`check-file-size.sh` rejects Java source files over 1,200 lines. Override the
limit for local evaluation with `MAX_FILE_LINES=<limit>`.
