/**********************************************************************
SScNAGlyphs
SMuFL glyph lookup for SuperScore.

Uses MITHUnicode (Tom Hall, GPL-3) for codepoint -> UTF-8 conversion:
https://github.com/ludions/scbook-2nd-edn/tree/main/26.2
Glyph names: SMuFL glyphnames.json, https://github.com/w3c/smufl/tree/gh-pages/metadata

Claudio Panariello
Email: 	claudio@claudiopanariello.com
URL:	https://claudiopanariello.com/
**********************************************************************/

SScNAGlyphs {
	classvar <glyphs, <accidentalForDeviation;

	*load { |glyphnamesPath|
		var parsed;
		if(\MITHUnicode.asClass.isNil) {
			"SScNaGlyphs: MITHUnicode not installed, loading fallback glyphs. Pray for the best".warn;
			^this.loadFallback;
		};
		if(glyphnamesPath.isNil or: { File.exists(glyphnamesPath).not }) {
			"SScNaGlyphs: glyphnames.json not found, loading fallback glyphs. Pray for the best.".warn;
			^this.loadFallback;
		};
		parsed = File.readAllString(glyphnamesPath).parseJSON;
		glyphs = IdentityDictionary.new(parsed.size);
		parsed.keysValuesDo { |name, info|
			glyphs.put(name.asSymbol, info["codepoint"].asGlyph);
		};
		format("SScNaGlyphs: % glyphs loaded.", glyphs.size).postln;
		this.initAccidentals;
	}

	*loadFallback {
		glyphs = IdentityDictionary[
			//noteheads
			\noteShapeRoundBlack -> "",
			\noteShapeDiamondWhite ->"",
			\noteShapeDiamondBlack ->"",
			\textBlackNoteLongStem -> "",
			\noteQuarterDown -> "",

			//clefs
			\gClef15ma -> "",
			\gClef -> "",
			\fClef -> "",
			\fClef15mb -> "",

			//accidentals
			\accidentalDoubleFlatOneArrowUp -> "",
			\accidentalDoubleFlatTwoArrowsUp -> "",
			\accidentalDoubleFlatThreeArrowsUp -> "",
			\accidentalThreeQuarterTonesFlatZimmermann -> "", // classic threeQuarterTonesFlat
			\accidentalFlatThreeArrowsDown -> "",
			\accidentalFlatTwoArrowsDown -> "",
			\accidentalFlatOneArrowDown -> "", // flat with arrow DOWN
			\accidentalFlat -> "",
			\accidentalFlatOneArrowUp -> "", // flat with arrow UP
			\accidentalFlatTwoArrowsUp -> "",
			\accidentalFlatThreeArrowsUp -> "",
			\accidentalQuarterToneFlatStein -> "", //classic quarter tone flat
			\accidentalNaturalThreeArrowsDown -> "",
			\accidentalNaturalTwoArrowsDown -> "",
			\accidentalNaturalOneArrowDown -> "",
			\accidentalNatural -> "",
			\accidentalNaturalOneArrowUp -> "",
			\accidentalNaturalTwoArrowsUp -> "",
			\accidentalNaturalThreeArrowsUp -> "",
			\accidentalQuarterToneSharpStein -> "", //classic quarter tone sharp
			\accidentalSharpThreeArrowsDown -> "",
			\accidentalSharpTwoArrowsDown -> "",
			\accidentalSharpOneArrowDown -> "", // sharp with arrow DOWN
			\accidentalSharp -> "",
			\accidentalSharpOneArrowUp -> "", // sharp with arrow UP
			\accidentalSharpTwoArrowsUp -> "",
			\accidentalSharpThreeArrowsUp -> "",
			\accidentalThreeQuarterTonesSharpStein -> "", // classic threeQuarterTonesSharp
			\accidentalDoubleSharpThreeArrowsDown -> "",
			\accidentalDoubleSharpTwoArrowsDown -> "",
			\accidentalDoubleSharpOneArrowDown -> "",
		];
		this.initAccidentals;
	}

	*initAccidentals {
		accidentalForDeviation = IdentityDictionary[
			-15 -> \accidentalDoubleFlatOneArrowUp,
			-14 -> \accidentalDoubleFlatTwoArrowsUp,
			-13 -> \accidentalDoubleFlatThreeArrowsUp,
			-12 -> \accidentalThreeQuarterTonesFlatZimmermann,
			-11 -> \accidentalFlatThreeArrowsDown,
			-10 -> \accidentalFlatTwoArrowsDown,
			-9 -> \accidentalFlatOneArrowDown,
			-8 -> \accidentalFlat,
			-7 -> \accidentalFlatOneArrowUp,
			-6 -> \accidentalFlatTwoArrowsUp,
			-5 -> \accidentalFlatThreeArrowsUp,
			-4 -> \accidentalQuarterToneFlatStein,
			-3 -> \accidentalNaturalThreeArrowsDown,
			-2 -> \accidentalNaturalTwoArrowsDown,
			-1 -> \accidentalNaturalOneArrowDown,
			0 -> \accidentalNatural,
			1 -> \accidentalNaturalOneArrowUp,
			2 -> \accidentalNaturalTwoArrowsUp,
			3 -> \accidentalNaturalThreeArrowsUp,
			4 -> \accidentalQuarterToneSharpStein,
			5 -> \accidentalSharpThreeArrowsDown,
			6 -> \accidentalSharpTwoArrowsDown,
			7 -> \accidentalSharpOneArrowDown,
			8 -> \accidentalSharp,
			9 -> \accidentalSharpOneArrowUp,
			10 -> \accidentalSharpTwoArrowsUp,
			11 -> \accidentalSharpThreeArrowsUp,
			12 -> \accidentalThreeQuarterTonesSharpStein,
			13 -> \accidentalDoubleSharpThreeArrowsDown,
			14 -> \accidentalDoubleSharpTwoArrowsDown,
			15 -> \accidentalDoubleSharpOneArrowDown,
		];

		// safety check
		accidentalForDeviation.keysValuesDo { |dev, name|
			if(glyphs[name].isNil) {
				format("SScNaGlyphs: accidentalForDeviation[%] -> '%' not found", dev, name).warn;
			};
		};
	}

	*at { |name|
		if(glyphs.isNil) { this.load };
		^glyphs.atFail(name.asSymbol, {
			format("SScNaGlyphs: glyph '%' not found", name).warn;
		});
	}

	*accidental { |deviation|
		var name = accidentalForDeviation[deviation];
		^if(name.isNil) { "" } { this.at(name) };
	}
}