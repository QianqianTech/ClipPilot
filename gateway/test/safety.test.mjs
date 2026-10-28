import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { writeFile } from "node:fs/promises";
import { join } from "node:path";
import test from "node:test";

import {
  classifySafety,
  containsUnsafeTerm,
  normalizeGenerationRequest,
  normalizeKeywords,
  sanitizeCandidatePayload,
} from "../src/protocol.mjs";
import {
  DEFAULT_SAFETY_TERMS_FILE,
  getSafetyTerms,
  loadSafetyTerms,
  parseSafetyTerms,
} from "../src/safety-terms.mjs";
import { requestBody, withTempDir } from "./helpers.mjs";

const SHARED_FILE = JSON.parse(readFileSync(DEFAULT_SAFETY_TERMS_FILE, "utf8"));

test("shared vocabulary is loaded from config/safety-terms.json", () => {
  const terms = getSafetyTerms();
  assert.equal(terms.version, SHARED_FILE.version);
  assert.equal(terms.block.length, SHARED_FILE.block.length);
  assert.equal(terms.review.length, SHARED_FILE.review.length);
  assert.equal(terms.allowPhrases.length, SHARED_FILE.allow_phrases.length);
});

test("classifySafety: block beats review, allow phrases are removed first", () => {
  assert.equal(classifySafety("这是一段普通的学习分享"), "ok");
  assert.equal(classifySafety("小心诈骗电话"), "block");
  assert.equal(classifySafety("反诈骗宣传周活动"), "ok");
  assert.equal(classifySafety("防诈骗也要防赌博"), "block");
  assert.equal(classifySafety("暴力美学的镜头语言"), "ok");
  assert.equal(classifySafety("这段打斗太暴力了"), "review");
  assert.equal(classifySafety("暴力和赌博"), "block");
  // Lowercasing applies to the text: "加V" matches review term "加v".
  assert.equal(classifySafety("想要资料加V"), "review");
  // Removing an allow phrase can join the remaining text into a blocked term.
  assert.equal(classifySafety("诈反诈骗骗"), "block");
  assert.equal(classifySafety(""), "ok");
  assert.equal(classifySafety(null), "ok");

  assert.equal(containsUnsafeTerm("反诈骗"), false);
  assert.equal(containsUnsafeTerm("刷单返利"), true);
  assert.equal(containsUnsafeTerm("诈骗"), true);
});

test("classifySafety accepts an explicit vocabulary and lowercases allow phrases", () => {
  const terms = parseSafetyTerms({
    version: "t1",
    block: ["bad"],
    review: ["meh"],
    allow_phrases: ["NOT BAD"],
  });
  assert.equal(classifySafety("This is Not Bad at all", terms), "ok");
  assert.equal(classifySafety("This is BAD", terms), "block");
  assert.equal(classifySafety("Meh.", terms), "review");
});

test("safety terms file path is overridable and malformed vocabularies are rejected", async () => {
  await withTempDir(async (folder) => {
    const custom = join(folder, "terms.json");
    await writeFile(custom, JSON.stringify({ version: "custom-1", block: ["foo"], review: [], allow_phrases: [] }));
    const previous = process.env.GATEWAY_SAFETY_TERMS_FILE;
    process.env.GATEWAY_SAFETY_TERMS_FILE = custom;
    try {
      const terms = loadSafetyTerms();
      assert.equal(terms.version, "custom-1");
      assert.equal(classifySafety("FOO bar", terms), "block");
    } finally {
      if (previous === undefined) {
        delete process.env.GATEWAY_SAFETY_TERMS_FILE;
      } else {
        process.env.GATEWAY_SAFETY_TERMS_FILE = previous;
      }
    }
  });
  assert.throws(() => parseSafetyTerms({ version: "x", block: [""], review: [], allow_phrases: [] }), /block/);
  assert.throws(() => parseSafetyTerms({ block: [], review: [], allow_phrases: [] }), /格式无效/);
  assert.throws(() => parseSafetyTerms({ version: "x", block: [], review: "暴力", allow_phrases: [] }), /review/);
});

test("context classification: block rejects, review accepts with labels, allow phrases pass", () => {
  assert.throws(() => normalizeGenerationRequest(requestBody("req-block", {
    video_context: { source: "accessibility_text", text: "这里在教大家参与网络赌博的方法" },
  })), (reason) => reason.status === 422 && reason.code === "UNSAFE_CONTEXT");

  const allowed = normalizeGenerationRequest(requestBody("req-allow", {
    video_context: { source: "accessibility_text", text: "社区民警讲解反诈骗知识，提醒老人注意" },
  }));
  assert.equal(allowed.contextReview, false);

  const review = normalizeGenerationRequest(requestBody("req-review", {
    video_context: { source: "accessibility_text", text: "电影里这场打斗戏有点暴力，但剪辑节奏很好" },
  }));
  assert.equal(review.contextReview, true);
  const result = sanitizeCandidatePayload({
    candidates: [
      { text: "剪辑节奏确实很好，镜头切换很有张力。", evidence_label: "剪辑" },
      { text: "喜欢的话私信我领取完整素材包吧。", evidence_label: "素材" },
    ],
  }, review);
  assert.equal(result.context_review, true);
  assert.equal(result.candidates.length, 1);
  assert.deepEqual(result.candidates[0].safety_labels, ["context_review"]);

  const okResult = sanitizeCandidatePayload({
    candidates: [{ text: "剪辑节奏确实很好，镜头切换很有张力。" }],
  }, allowed);
  assert.equal(okResult.context_review, false);
  assert.deepEqual(okResult.candidates[0].safety_labels, []);
});

test("keywords classified block or review are dropped", () => {
  assert.deepEqual(normalizeKeywords(["学习计划", "赌博", "暴力", "反诈骗", "加V领取"]), ["学习计划", "反诈骗"]);
});
