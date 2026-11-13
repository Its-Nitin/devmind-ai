// @ts-check
// Lint gates for the frontend (coding standards FE-1..FE-5, FE-11, FE-12, SEC-9). `npm run lint` runs it; CI fails on errors.
import eslint from '@eslint/js';
import angular from 'angular-eslint';
import prettier from 'eslint-config-prettier';
import boundaries from 'eslint-plugin-boundaries';
import { defineConfig } from 'eslint/config';
import tseslint from 'typescript-eslint';

/**
 * FE-5: `core/` holds app-wide singletons, `shared/` dependency-free UI, and each `features/<name>/` one lazy-loaded
 * feature. A feature may use core, shared and itself, never another feature.
 */
const architecture = {
  plugins: { boundaries },
  settings: {
    'boundaries/root-path': import.meta.dirname,
    'import/resolver': { node: { extensions: ['.ts', '.js'] } },
    'boundaries/elements': [
      { type: 'feature', pattern: 'src/app/features/*', capture: ['feature'], partialMatch: false },
      { type: 'core', pattern: 'src/app/core', partialMatch: false },
      { type: 'shared', pattern: 'src/app/shared', partialMatch: false },
      // Folders nest: a file belongs to its deepest matching element, so `app` and `bootstrap` are the files left over.
      { type: 'app', pattern: 'src/app', partialMatch: false },
      { type: 'bootstrap', pattern: 'src', partialMatch: false },
    ],
  },
  rules: {
    'boundaries/dependencies': [
      'error',
      {
        default: 'disallow',
        message: 'FE-5: {{ from.element.types.[0] }} code may not import {{ to.element.types.[0] }} code',
        policies: [
          { from: { element: { type: 'bootstrap' } }, allow: { to: { element: { types: ['app'] } } } },
          {
            from: { element: { type: 'app' } },
            allow: { to: { element: { types: ['app', 'core', 'shared', 'feature'] } } },
          },
          { from: { element: { type: 'core' } }, allow: { to: { element: { types: ['core', 'shared'] } } } },
          { from: { element: { type: 'shared' } }, allow: { to: { element: { type: 'shared' } } } },
          {
            from: { element: { type: 'feature' } },
            allow: [
              { to: { element: { types: ['core', 'shared'] } } },
              {
                to: {
                  element: { type: 'feature', captured: { feature: '{{ from.element.captured.feature }}' } },
                },
              },
            ],
          },
        ],
      },
    ],
  },
};

export default defineConfig([
  { ignores: ['dist/', 'coverage/', '.angular/', 'src/app/core/api/schema.d.ts'] },
  {
    files: ['**/*.ts'],
    extends: [
      eslint.configs.recommended,
      tseslint.configs.strictTypeChecked,
      tseslint.configs.stylisticTypeChecked,
      angular.configs.tsRecommended,
    ],
    languageOptions: {
      parserOptions: { projectService: true, tsconfigRootDir: import.meta.dirname },
    },
    processor: angular.processInlineTemplates,
    rules: {
      '@angular-eslint/directive-selector': ['error', { type: 'attribute', prefix: 'dm', style: 'camelCase' }],
      '@angular-eslint/component-selector': ['error', { type: 'element', prefix: 'dm', style: 'kebab-case' }],
      // FE-1: no `any`; a `!` needs an eslint-disable comment that says why.
      '@typescript-eslint/no-explicit-any': 'error',
      '@typescript-eslint/no-non-null-assertion': 'error',
      // Angular classes are often empty apart from their decorator (the shell, route components).
      '@typescript-eslint/no-extraneous-class': ['error', { allowWithDecorator: true }],
      // FE-2: OnPush is Angular 22's default; this flags components that opt out with Eager/Default.
      '@angular-eslint/prefer-on-push-component-change-detection': 'error',
      // FE-3, FE-4: signals, inject(), signal outputs, no forgotten subscriptions.
      '@angular-eslint/prefer-inject': 'error',
      '@angular-eslint/prefer-signals': 'error',
      '@angular-eslint/prefer-output-emitter-ref': 'error',
      '@angular-eslint/no-uncalled-signals': 'error',
      '@angular-eslint/no-implicit-take-until-destroyed': 'error',
      // SEC-9: repository and model content is never trusted as HTML, URLs or scripts.
      'no-restricted-syntax': [
        'error',
        {
          selector: 'MemberExpression[property.name=/^bypassSecurityTrust/]',
          message: 'SEC-9: never bypass Angular sanitization.',
        },
        {
          selector: 'CallExpression[callee.name="provideZoneChangeDetection"]',
          message: 'FE-2: the app is zoneless; do not bring zone.js change detection back.',
        },
      ],
    },
  },
  {
    files: ['**/*.html'],
    extends: [angular.configs.templateRecommended, angular.configs.templateAccessibility],
    rules: {
      '@angular-eslint/template/prefer-control-flow': 'error', // FE-4
      '@angular-eslint/template/no-any': 'error', // FE-1
      '@angular-eslint/template/button-has-type': 'error', // FE-11
      'no-restricted-syntax': [
        'error',
        {
          selector: 'BoundAttribute[name=/^(innerHTML|outerHTML)$/]',
          message: 'SEC-9/FE-12: render repository and model content as text, not HTML.',
        },
      ],
    },
  },
  { files: ['src/**/*.ts'], ...architecture },
  prettier, // last: turns off rules that would fight Prettier
]);
