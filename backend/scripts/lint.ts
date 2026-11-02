import path from "node:path";
import fs from "node:fs";
import ts from "typescript";
import { ESLint, type Linter } from "eslint";

async function main(): Promise<void> {
    const root = path.resolve(__dirname, "../..");
    const configPath = path.join(root, ".eslintrc.json");
    // eslint-disable-next-line security/detect-non-literal-fs-filename -- Fixed repository configuration path.
    const parsed = ts.parseConfigFileTextToJson(configPath, fs.readFileSync(configPath, "utf8"));
    if (parsed.error) throw new Error("Cannot parse .eslintrc.json");
    const config = parsed.config as Linter.Config;
    config.parserOptions = { ...config.parserOptions, tsconfigRootDir: root };
    config.parser = require.resolve("@typescript-eslint/parser");
    if (!Array.isArray(config.extends)) throw new Error("Expected an array of ESLint presets");
    config.extends = config.extends.map(name => name === "standard"
        ? require.resolve("eslint-config-standard")
        : name);
    const eslint = new ESLint({
        cwd: root,
        useEslintrc: false,
        baseConfig: config,
        resolvePluginsRelativeTo: path.resolve(__dirname, ".."),
        overrideConfig: { ignorePatterns: ["backend/src/generated/**"] },
    });
    const files = process.argv.slice(2);
    const defaults = ["backend/src/**/*.ts", "backend/tests/**/*.ts", "backend/scripts/**/*.ts"];
    const results = await eslint.lintFiles(files.length ? files : defaults);
    const formatter = await eslint.loadFormatter("stylish");
    process.stdout.write(await formatter.format(results));
    if (results.some(result => result.errorCount > 0 || result.warningCount > 0)) process.exitCode = 1;
}
main().catch((error: unknown) => {
    console.error(error);
    process.exitCode = 1;
});
