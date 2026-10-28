import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname } from "node:path";

const PROFILE_IDS = ["standard", "high_relevance", "economy"];
const DEFAULT_DOCUMENT = Object.freeze({
  version: 1,
  active_profile: "standard",
  profiles: {
    standard: { model: "gpt-4o-mini", temperature: 0.35, max_output_tokens: 220 },
    high_relevance: { model: "gpt-4o-mini", temperature: 0.2, max_output_tokens: 280 },
    economy: { model: "gpt-4o-mini", temperature: 0.45, max_output_tokens: 160 },
  },
});

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

function profileFrom(input) {
  if (!input || typeof input !== "object" || Array.isArray(input)) {
    throw new Error("模型档位必须是对象。");
  }
  const allowedKeys = new Set(["model", "temperature", "max_output_tokens"]);
  for (const key of Object.keys(input)) {
    if (!allowedKeys.has(key)) {
      throw new Error("模型档位不能包含密钥、供应商地址或未支持字段。");
    }
  }
  const model = typeof input.model === "string" ? input.model.trim() : "";
  const temperature = Number(input.temperature);
  const maxOutputTokens = Number(input.max_output_tokens);
  if (!/^[A-Za-z0-9._:/-]{1,100}$/.test(model)
      || !Number.isFinite(temperature) || temperature < 0 || temperature > 1
      || !Number.isInteger(maxOutputTokens) || maxOutputTokens < 64 || maxOutputTokens > 512) {
    throw new Error("模型档位参数无效。");
  }
  return { model, temperature, max_output_tokens: maxOutputTokens };
}

export function defaultProfileDocument() {
  return clone(DEFAULT_DOCUMENT);
}

export function validateProfileDocument(input) {
  if (!input || typeof input !== "object" || Array.isArray(input)) {
    throw new Error("配置必须是对象。");
  }
  const activeProfile = typeof input.active_profile === "string" ? input.active_profile : "";
  if (!PROFILE_IDS.includes(activeProfile)) {
    throw new Error("active_profile 无效。");
  }
  if (!input.profiles || typeof input.profiles !== "object" || Array.isArray(input.profiles)) {
    throw new Error("profiles 无效。");
  }
  const profiles = {};
  for (const profileId of PROFILE_IDS) {
    profiles[profileId] = profileFrom(input.profiles[profileId]);
  }
  if (Object.keys(input.profiles).some((key) => !PROFILE_IDS.includes(key))) {
    throw new Error("存在不支持的模型档位。 ");
  }
  return { version: 1, active_profile: activeProfile, profiles };
}

export class ProfileStore {
  constructor(filePath) {
    this.filePath = filePath;
  }

  async read() {
    try {
      const parsed = JSON.parse(await readFile(this.filePath, "utf8"));
      return validateProfileDocument(parsed);
    } catch (error) {
      if (error?.code === "ENOENT") {
        return defaultProfileDocument();
      }
      throw error;
    }
  }

  async write(input) {
    const document = validateProfileDocument(input);
    await mkdir(dirname(this.filePath), { recursive: true, mode: 0o700 });
    const temporaryPath = `${this.filePath}.${process.pid}.${Date.now()}.tmp`;
    await writeFile(temporaryPath, `${JSON.stringify(document, null, 2)}\n`, {
      encoding: "utf8",
      mode: 0o600,
    });
    await rename(temporaryPath, this.filePath);
    return document;
  }
}
