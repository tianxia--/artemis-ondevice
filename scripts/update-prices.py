#!/usr/bin/env python3
"""Refreshes app/src/main/assets/prices.json from LiteLLM's model price list (MIT, BerriAI).

Keeps chat models with input and output prices, one entry per model name (the part after the
last "/", lowercased), preferring the provider's own API over resellers and clouds. Prices are
USD per million tokens. Run before a release:  python3 scripts/update-prices.py
"""
import datetime
import json
import os
import urllib.request

SOURCE = "https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json"
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "prices.json")
# Earlier wins when two providers list the same model name.
PREFERRED = ["openai", "anthropic", "gemini", "deepseek", "mistral", "xai", "dashscope", "qwen_ai_platform",
             "moonshot", "zai", "minimax", "volcengine", "groq", "cohere", "perplexity", "openrouter",
             "together_ai", "fireworks_ai", "deepinfra"]


def rank(provider):
    return PREFERRED.index(provider) if provider in PREFERRED else len(PREFERRED)


def main():
    with urllib.request.urlopen(SOURCE, timeout=60) as r:
        data = json.load(r)
    best = {}
    for key, v in data.items():
        if not isinstance(v, dict) or v.get("mode") != "chat":
            continue
        cin, cout = v.get("input_cost_per_token"), v.get("output_cost_per_token")
        if not cin or not cout:
            continue
        name = key.rsplit("/", 1)[-1].lower()
        provider = v.get("litellm_provider", "")
        entry = [round(cin * 1e6, 6), round(cout * 1e6, 6), provider]
        if name not in best or rank(provider) < rank(best[name][2]):
            best[name] = entry
    out = {
        "source": "LiteLLM model_prices_and_context_window.json, (c) Berri AI, MIT license",
        "url": SOURCE,
        "fetched": datetime.date.today().isoformat(),
        "currency": "USD",
        "unit": "per million tokens",
        "models": dict(sorted(best.items())),
    }
    with open(OUT, "w") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    print(f"{len(best)} models -> {os.path.relpath(OUT)} ({os.path.getsize(OUT) // 1024} KB)")


if __name__ == "__main__":
    main()
