/*
 * ブロックの名前から、上から見た地図の色。
 *
 * 全ブロックの表は持たない。会場（2026-09-13 の町）の一番上に出ていたブロックを数えると、水・草・荒い土・安山岩・
 * 石・固めた泥・石のハーフで8割を超え、残りは Create の石材（凝灰岩・石灰岩…）と銅と深層岩だった。だから
 * 名前に含まれる素材の言葉で決め、決まらない物だけ名前から作った落ち着いた色にする。
 */
(function (AV) {
  "use strict";

  const DYES = {
    white: [233, 236, 236], orange: [240, 118, 19], magenta: [189, 68, 179], light_blue: [58, 175, 217],
    yellow: [248, 197, 39], lime: [112, 185, 25], pink: [237, 141, 172], gray: [62, 68, 71],
    light_gray: [142, 142, 134], cyan: [21, 137, 145], purple: [121, 42, 172], blue: [53, 57, 157],
    brown: [114, 71, 40], green: [84, 109, 27], red: [160, 39, 34], black: [20, 21, 25],
  };

  const WOODS = {
    dark_oak: [72, 48, 24], oak: [162, 130, 78], spruce: [114, 84, 48], birch: [196, 179, 123],
    jungle: [160, 115, 80], acacia: [168, 90, 50], mangrove: [117, 54, 48], cherry: [226, 178, 172],
    bamboo: [194, 173, 81], crimson: [101, 48, 70], warped: [43, 104, 99],
  };

  /** 上から順に最初に当たった物。[言葉, 色]。言葉は名前（名前空間を除いた部分）に含まれるか。 */
  const MATERIALS = [
    ["water", [58, 102, 204]], ["bubble_column", [58, 102, 204]], ["lava", [207, 92, 20]],
    ["grass_block", [104, 146, 60]], ["moss", [89, 110, 45]],
    ["birch_leaves", [120, 160, 80]], ["azalea_leaves", [92, 122, 52]], ["spruce_leaves", [60, 95, 60]],
    ["cherry_leaves", [230, 170, 190]], ["leaves", [70, 118, 48]],
    ["short_grass", [98, 140, 58]], ["tall_grass", [98, 140, 58]], ["fern", [90, 132, 55]], ["dripleaf", [96, 140, 50]],
    ["powder_snow", [240, 245, 250]], ["snow", [240, 245, 250]], ["blue_ice", [116, 167, 253]], ["ice", [150, 190, 240]],
    ["red_sandstone", [181, 98, 31]], ["sandstone", [216, 203, 155]], ["red_sand", [190, 102, 33]], ["sand", [219, 207, 163]],
    ["gravel", [136, 126, 122]], ["clay", [160, 166, 179]], ["podzol", [91, 63, 24]], ["mycelium", [111, 98, 101]],
    ["packed_mud", [142, 106, 79]], ["mud_brick", [137, 104, 79]], ["mud", [60, 57, 60]],
    ["dirt_path", [148, 122, 65]], ["farmland", [110, 75, 45]], ["dirt", [134, 96, 67]],
    ["oxidized", [82, 162, 132]], ["weathered", [108, 153, 110]], ["exposed", [161, 125, 103]], ["copper", [192, 107, 79]],
    ["calcite", [223, 224, 220]], ["dripstone", [134, 107, 92]], ["tuff", [108, 109, 102]],
    ["deepslate_tile", [54, 54, 58]], ["deepslate", [80, 80, 86]], ["blackstone", [42, 36, 41]], ["basalt", [80, 80, 85]],
    ["andesite_alloy", [168, 168, 156]], ["andesite_casing", [150, 150, 140]], ["brass", [200, 155, 60]],
    ["andesite", [136, 136, 136]], ["diorite", [188, 188, 188]], ["granite", [149, 103, 85]],
    ["limestone", [182, 180, 165]], ["veridium", [60, 110, 90]], ["asurine", [80, 110, 170]], ["crimsite", [150, 60, 55]],
    ["ochrum", [185, 150, 85]], ["scorchia", [55, 50, 48]], ["scoria", [95, 75, 65]],
    ["nether_brick", [44, 22, 26]], ["netherrack", [111, 54, 52]], ["end_stone", [219, 222, 158]], ["purpur", [169, 125, 169]],
    ["dark_prismarine", [52, 92, 76]], ["prismarine_brick", [99, 171, 158]], ["prismarine", [99, 156, 151]],
    ["quartz", [235, 229, 222]],
    ["mossy_stone_brick", [115, 121, 105]], ["stone_brick", [122, 121, 122]], ["mossy_cobblestone", [110, 118, 94]],
    ["cobblestone", [127, 127, 127]], ["smooth_stone", [158, 158, 158]], ["stone", [125, 125, 125]],
    ["bricks", [150, 74, 58]], ["brick_", [150, 74, 58]],
    ["glass", [175, 213, 219]], ["iron", [200, 200, 200]], ["gold", [246, 208, 61]], ["diamond", [98, 237, 228]],
    ["emerald", [42, 203, 87]], ["redstone", [175, 24, 5]], ["lapis", [31, 67, 140]], ["coal", [30, 29, 29]],
    ["girder", [120, 120, 125]], ["metal", [120, 120, 125]], ["track", [110, 100, 90]], ["rail", [120, 110, 100]],
    ["lantern", [245, 210, 120]], ["torch", [245, 210, 120]], ["glowstone", [245, 210, 120]], ["shroomlight", [245, 170, 90]],
    ["hay_block", [166, 136, 38]], ["beehive", [180, 145, 90]], ["pumpkin", [198, 118, 24]], ["melon", [111, 145, 30]],
    ["obsidian", [20, 18, 30]], ["bedrock", [60, 60, 60]], ["bone_block", [226, 223, 200]],
  ];

  const cache = new Map();

  function mix(color, other, amount) {
    return [0, 1, 2].map((i) => Math.round(color[i] * (1 - amount) + other[i] * amount));
  }

  function hashed(name) {
    let hash = 2166136261;

    for (let i = 0; i < name.length; i++) {
      hash = Math.imul(hash ^ name.charCodeAt(i), 16777619);
    }

    // 灰色の上に少しだけ色味。知らないブロックを目立たせない。
    const tint = [(hash & 255) - 128, ((hash >>> 8) & 255) - 128, ((hash >>> 16) & 255) - 128];

    return [0, 1, 2].map((i) => Math.max(40, Math.min(215, 128 + Math.round(tint[i] * 0.25))));
  }

  function resolve(full) {
    const name = full.includes(":") ? full.slice(full.indexOf(":") + 1) : full;

    if (name === "air" || name === "cave_air" || name === "void_air") {
      return [0, 0, 0];
    }

    for (const dye of Object.keys(DYES).sort((a, b) => b.length - a.length)) {
      if (!name.startsWith(dye + "_")) {
        continue;
      }

      const base = DYES[dye];

      if (name.includes("terracotta")) {
        return mix(base, [120, 90, 70], 0.45);
      }

      if (name.includes("concrete") || name.includes("wool") || name.includes("carpet") || name.includes("glass")
          || name.includes("bed") || name.includes("banner") || name.includes("candle")) {
        return base;
      }
    }

    if (name === "terracotta") {
      return [152, 94, 67];
    }

    for (const [word, color] of MATERIALS) {
      if (name.includes(word)) {
        return color;
      }
    }

    for (const wood of Object.keys(WOODS)) {
      if (name.startsWith(wood + "_") || name.includes("_" + wood + "_") || name.startsWith("stripped_" + wood)) {
        const base = WOODS[wood];

        return name.includes("log") || name.includes("wood") ? mix(base, [40, 30, 20], 0.35) : base;
      }
    }

    return hashed(full);
  }

  /** そのブロックの色 [r, g, b]。 */
  function of(name) {
    let color = cache.get(name);

    if (!color) {
      color = resolve(name || "minecraft:air");
      cache.set(name, color);
    }

    return color;
  }

  AV.colors = { of, mix };
})(window.AV = window.AV || {});
