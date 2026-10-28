import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
export const DEFAULT_SAFETY_TERMS_FILE = resolve(HERE, "../config/safety-terms.json");

function termList(value, field) {
  if (!Array.isArray(value)
      || value.some((term) => typeof term !== "string" || term.length === 0)) {
    throw new Error(`安全词表字段 ${field} 无效。`);
  }
  return Object.freeze(value.map((term) => term.toLowerCase()));
}

/**
 * Validates the shared vocabulary document (version, block, review,
 * allow_phrases). All terms are lowercased once here so classification only
 * has to lowercase the input text.
 */
export function parseSafetyTerms(document) {
  if (!document || typeof document !== "object" || Array.isArray(document)
      || typeof document.version !== "string" || document.version.trim().length === 0) {
    throw new Error("安全词表格式无效。");
  }
  return Object.freeze({
    version: document.version,
    block: termList(document.block, "block"),
    review: termList(document.review, "review"),
    allowPhrases: termList(document.allow_phrases, "allow_phrases"),
  });
}

export function loadSafetyTerms(filePath = process.env.GATEWAY_SAFETY_TERMS_FILE || DEFAULT_SAFETY_TERMS_FILE) {
  return parseSafetyTerms(JSON.parse(readFileSync(filePath, "utf8")));
}

let loadedTerms = null;

/** Reads the vocabulary file once per process and reuses it afterwards. */
export function getSafetyTerms() {
  if (!loadedTerms) {
    loadedTerms = loadSafetyTerms();
  }
  return loadedTerms;
}

/**
 * Android mirrors this algorithm exactly; keep both in sync:
 * 1. lowercase the text (locale-independent, like Locale.ROOT);
 * 2. remove every occurrence of each allow phrase, in file order;
 * 3. any block term contained -> "block"; else any review term -> "review";
 *    else "ok".
 */
export function classifySafety(value, terms = getSafetyTerms()) {
  if (typeof value !== "string" || value.length === 0) {
    return "ok";
  }
  let text = value.toLowerCase();
  for (const phrase of terms.allowPhrases) {
    text = text.split(phrase).join("");
  }
  if (terms.block.some((term) => text.includes(term))) {
    return "block";
  }
  if (terms.review.some((term) => text.includes(term))) {
    return "review";
  }
  return "ok";
}
