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

## GitHub Social Preview & Open Graph Standard
- **Mandatory Creation**: For every project hosted on GitHub, design and export a dedicated high-resolution social preview card (1280x640, 1.91:1 ratio for GitHub Social Preview and X `summary_large_image`).
- **Aesthetic**: Modern studio canvas with subtle micro-dots, high-contrast headline with monospace accents, and 4 angled floating UI cards with realistic drop shadows highlighting authentic features.
- **Maintenance**: Keep editable source files in `banner/` and update the banner (version badges, metrics, and cards) on every major repository release. Store assets in `assets/social_preview.png`, `.github/social_preview.png`, and embed into `README.md`.

