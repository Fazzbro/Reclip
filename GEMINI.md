# Project Guidelines & Rules

## Web & Browser-Based Tasks Default
- **Browser Requirement**: Always use **Ego Lite Browser** (`ego-browser` / `ego-lite`) as the **DEFAULT** browser for all web-related tasks: web testing, browser automation, QA, form filling, web scraping, screenshots, DOM inspection, and UI verification.
- **Do not use standard Puppeteer, Playwright, or generic headless browsers** when Ego Lite is available.
- **Execution Workflow on Windows**:
  - Write test or automation scripts to `.mjs` files using standard Ego Lite task space APIs (`taskSpace()`, `task.page()`, `page.goto()`, `page.click()`, `page.snapshot()`, `task.finish({ keep: [] })`).
  - To prevent PowerShell quoting/REPL hanging issues, always execute scripts via input redirection:
    ```cmd
    cmd.exe /c "ego-browser nodejs < <absolute_path_to_script.mjs>"
    ```
