/**********************************************************************
 ▗▄▄▖▗▖ ▗▖▗▄▄▖ ▗▄▄▄▖▗▄▄▖  ▗▄▄▖ ▗▄▄▖ ▗▄▖ ▗▄▄▖ ▗▄▄▄▖
▐▌   ▐▌ ▐▌▐▌ ▐▌▐▌   ▐▌ ▐▌▐▌   ▐▌   ▐▌ ▐▌▐▌ ▐▌▐▌
 ▝▀▚▖▐▌ ▐▌▐▛▀▘ ▐▛▀▀▘▐▛▀▚▖ ▝▀▚▖▐▌   ▐▌ ▐▌▐▛▀▚▖▐▛▀▀▘
▗▄▄▞▘▝▚▄▞▘▐▌   ▐▙▄▄▖▐▌ ▐▌▗▄▄▞▘▝▚▄▄▖▝▚▄▞▘▐▌ ▐▌▐▙▄▄▖


SupercolliderSCoreNotationAssistant (SuperScore, or SScNa)
Displaying proportional notation natively in SC (something similar to a maxmsp bach.roll)

Class started on 2026-03 (but first code done in 2025)
Claudio Panariello
Email: 	claudio@claudiopanariello.com
URL:	https://claudiopanariello.com/


TO DO (not necessarily in this order eheh):
- Avoid collisions in chords
- Play the roll from a given timestamp
- Select a note from the GUI
- Add dynamics! Noteheads gray scale?
- Fix the way midiNotes is interpreted: when it starts with a chord, it needs some extra brackets in order to get properly recognized
- Automatically stop the playbackline when reaches the end of the roll
- Refactor the entire class using SuperScoreEvent !

**********************************************************************/

SuperScore {
	classvar musicFont = "Bravura";
	classvar offsetY = 76; //<----------- IMPORTANT! For Bravura offsetY = 76; for Petaluma offsetY = 38

	var <>midiNotes, <>rhythms, <>clefs, <>windowTitle, <>scale, <>defaultSpacing, <>forceAccidentals, <>showDurations, <>showStems, <>windowWidth, <>windowHeight, <>posX, <>posY, <>parent;
	var <>window, <>userView;
	var <>durations;
	var <>staffPosition;
	var <>staffWidth, <> staffHeight;
	var <>ledgerLinesBounds;
	var <>toneDivision, majorScale;

	var prevNote, prevNotePosX, prevNotePosY, prevNotePosYchord;
	var direction;
	var numStaves;
	var tempoBpm = 60;
	var <>playbackPoint = 0, pixelsPerSecond = 0, startTimePlayback = 0, playbackOffset = 0;
	var <>scrollView;
	var isPaused = false, pauseTime = 0, players;
	var isPlaying = false;
	var <>midiOut;

	var distanceInBetweenStaves;
	var spaceInterline;
	var smuflDictionary, accidentalForDeviation;

	*new { |midiNotes = 60, rhythms = nil, clefs = \g, windowTitle = "Music Roll", scale = 1, defaultSpacing = 100, forceAccidentals = false, showDurations = true, showStems = true, windowWidth = 500, windowHeight = 500, posX = 600, posY = 300, parent = nil|
		^super.newCopyArgs(midiNotes.asArray, rhythms, clefs, windowTitle, scale, defaultSpacing, forceAccidentals, showDurations, showStems, windowWidth, windowHeight, posX, posY, parent).init;
	}

	init {
		//var auxMaxLength, auxLength;
		staffPosition = 200;
		spaceInterline = 10;
		toneDivision = 8; // or 16 for 96TET, etc.
		majorScale = Scale.major.degrees*(toneDivision*0.5);

		MIDIClient.initialized.not.if { MIDIClient.init };
		MIDIIn.connectAll;

		if(Font.availableFonts.includesEqual("Bravura") == false, {
			"Bravura font is missing. Please install from https://github.com/steinbergmedia/bravura and restart SC".throw;
		});

		if(Font.availableFonts.includesEqual("Petaluma") == false, {
			"Petaluma font is missing. Please install from https://github.com/steinbergmedia/petaluma and restart SC".throw;
		});

		midiNotes = midiNotes.fixShape;
		numStaves = midiNotes.shape[0];
		format("There are % staves in total.", numStaves).postln;
		this.fixDurations;

		//fix clefs: I need an array of numStaves elements, all strings
		clefs = clefs.asArray;
		distanceInBetweenStaves = 100*(clefs.maxItem.asString.size);
		if(clefs.size<numStaves, {clefs = clefs ++ (clefs.last!(numStaves-clefs.size))});
		clefs = clefs.collect({|i| i.asString});

		//auxMaxLength = durations.asArray.shape.postln;
		//auxLength = durations[auxMaxLength].collect({|i| i[2].abs}); //.sum*defaultSpacing;
		//("auxLength: "++auxLength).postln;

		//staffWidth = windowWidth.max(scale*midiNotes.shape.maxItem*defaultSpacing + 60);
		//staffWidth.postln;
		//staffHeight = windowHeight.max(staffPosition+(4*spaceInterline*numStaves)+(distanceInBetweenStaves*numStaves))*scale;
		this.updateCanvasSize; // this should now work better in displaying the correct window length
		this.loadSmuflDictionary;
		this.buildGui;
	}

	createWindow { |title|
		window = Window.new(
			title,
			Rect(posX, posY, windowWidth, windowHeight),
			resizable: true,
		).alwaysOnTop_(true).front;

		scrollView = ScrollView.new(window, Rect(0, 0, windowWidth, windowHeight))
		.resize_(5)
		.autohidesScrollers_(true)
		.hasVerticalScroller_(false);

		window.onClose_({
			CmdPeriod.remove(this);
			this.stop;
		});
	}

	fixDurations {
		//brutal way to create a rhythms array identical to midiNotes but filled with 1s (or the given value, only if it's ONE value)
		if(rhythms.isNil or: {rhythms.size==0}, {
			var auxValue = rhythms ?? 1;
			rhythms = List.new;
			numStaves.do({|n|
				rhythms.add(midiNotes[n].collect({ auxValue }));
			});
			rhythms = rhythms.asArray;
			rhythms.postln;
		});

		rhythms = rhythms.fixShape;
		durations = List.new;
		numStaves.do({|n|
			var onsets, isRest, triplets;
			onsets  = ([0] ++ rhythms[n].abs.integrate).drop(-1);
			isRest  = rhythms[n].collect { |d| d < 0 };
			triplets = [onsets, isRest, rhythms[n].abs].flop;
			durations.add(triplets); //triplets onsets-isRest-actualDuration
		});
	}

	renderMusic {
		Pen.scale(scale, scale);
		numStaves.do({|n|
			var currentStave = midiNotes[n];
			var offsetStaff = n*(distanceInBetweenStaves);
			var durs = durations[n].select({|item| item[1]==false}); //strip all the rests
			this.drawStaff(offsetStaff, clefs[n]);
			//format("Stave n.% has % events", n+1, currentStave.size).postln;
			prevNotePosY = nil;
			currentStave.do({|event, idx|
				var posX;
				prevNotePosYchord = nil;
				posX = 80 + (durs[idx][0] * defaultSpacing);
				if(event.isArray,
					{
						event.sort; //just to make sure
						event.do({|item| this.addNote(item, posX, offsetStaff, durs[idx][2]*defaultSpacing)})},
					{
						this.addNote(event, posX, offsetStaff, durs[idx][2]*defaultSpacing)}
				);
			});
		});
		this.playbackLine(ledgerLinesBounds[0][3]);
	}

	addNote {|inputNote = 66, posX, offsetStaff = 0, currentDuration = 0|  // f#4, space 235+accidental
		var stepsPerSemitone = (toneDivision * 0.5).asInteger;
		var stepsPerOctave   = 12 * stepsPerSemitone;
		var absStep          = (inputNote * stepsPerSemitone).round.asInteger; // 48TET step from MIDI 0
		var octave           = (absStep div: stepsPerOctave) - 1;
		var step48           = absStep % stepsPerOctave;
		var posY, ledgerLines = 0;

		// direction awareness to pick the sharp or flat accident
		var direction, directionChoice, nearestDiatonicStep, deviation;
		var minInterval = 0.5; // quartertone, in semitones
		var maxDeviation = 4; // half a tone in 48TET steps
		var interval = if(prevNote.notNil, { (inputNote - prevNote).abs }, { 0 });

		direction = if(prevNote.notNil && (interval >= minInterval), {
			if(inputNote > prevNote, { \ascending },
				if(inputNote < prevNote, { \descending },
					{ \unison }))
		}, { \unison });

		directionChoice = switch(direction,
			\ascending,  { majorScale.select{ |x| x <= step48 }.last ?? { majorScale[0] } },
			\descending, { majorScale.detect{ |x| x >= step48 } ?? { majorScale.last } },
			\unison,     { step48.nearestInList(majorScale) }
		);

		nearestDiatonicStep = if((step48 - directionChoice).abs <= maxDeviation,
			{ directionChoice },
			{ step48.nearestInList(majorScale) }
		);

		deviation = step48 - nearestDiatonicStep;

		// posY from scale degree index
		posY = staffPosition + 50 - (majorScale.indexOf(nearestDiatonicStep) * 5);
		posY = posY + (35 * (4 - octave)) + offsetStaff;

		// check and resolve collisions: TO BE DONE
		/*
		if(prevPosX.isNil, {prevPosX = posX});
		if(prevPosX==posX and:(inputNote-prevNote)<4, {
		avoidCollision = posY

		}
		);
		*/
		if(showDurations==true, {this.addDurationLine(posX, posY, currentDuration;)});

		Pen.stringAtPoint(
			smuflDictionary[\noteShapeRoundBlack],
			(posX-7)@(posY-offsetY),
			Font(musicFont, 38),
			Color.black;
		);

		// add ledger lines (if needed)
		this.checkLedgerLine(inputNote, posX, posY);
		this.addAccidental(deviation, posX, posY, prevNotePosY);
		if(showStems==true, {this.addStem(posX, posY, prevNotePosYchord)});

		prevNote = inputNote;
		prevNotePosX = posX;
		prevNotePosY = posY;
		prevNotePosYchord = posY;
	}

	addAccidental {|deviation, posX, posY, prevPosY|
		if(forceAccidentals == false, {
			if(prevPosY != posY and: {deviation==0}, {
				deviation = 9999})
		});
		Pen.stringAtPoint(accidentalForDeviation[deviation], (posX-21)@(posY-offsetY), Font(musicFont, 38), Color.black;);
	}

	addStem {|posX, posY, prevPosY|
		if(prevPosY.isNil, {prevPosY = posY});
		Pen.strokeColor_(Color.black);
		Pen.width_(1);
		Pen.line(
			Point(posX-7, posY),
			Point(posX-7, prevPosY+34);
		);
		Pen.stroke;
	}

	addDurationLine {|posX, posY, dur|
		Pen.strokeColor_(Color.black);
		Pen.width_(4);
		Pen.line(
			Point(posX, posY),
			Point(posX-7+dur, posY);
		);
		Pen.stroke;
	}

	// this method checks if, based on the note and on the clefs, the ledger lines are needed.
	// Then it calls the actual addLedgerLine method with the right number of ledgerLines
	checkLedgerLine {|inputNote, posX, posY|
		var myStaff, staffTopY, staffBottomY, ledgerLines = 0;

		// find which staff this note belongs to
		myStaff = ledgerLinesBounds.detect({|b| inputNote >= b[0] and: (inputNote <= b[1])});

		// if between staves or outside all staves, snap to nearest
		if(myStaff.isNil, {
			myStaff = ledgerLinesBounds.minItem({|b|
				min((inputNote - b[1]).abs, (inputNote - b[0]).abs)
			});
		});

		staffTopY    = myStaff[2];
		staffBottomY = myStaff[3];

		// ledger lines above
		if(posY < (staffTopY - (0.5 * spaceInterline)), {
			ledgerLines = ((staffTopY - posY) / spaceInterline).floor.asInteger;
		});

		// ledger lines below
		if(posY > (staffBottomY + (0.5 * spaceInterline)), {
			ledgerLines = ((staffBottomY - posY) / spaceInterline).ceil.asInteger; // negative
		});

		this.addLedgerLine(ledgerLines, posX, staffTopY, staffBottomY);
	}


	addLedgerLine {|num, posX, staffTopY, staffBottomY|
		if(num > 0, {
			num.do({|idx|
				var lineY = staffTopY - ((1 + idx) * spaceInterline);
				Pen.strokeColor_(Color.black);
				Pen.width_(1);
				Pen.line(Point(posX - spaceInterline, lineY), Point(posX + spaceInterline, lineY));
				Pen.stroke;
			});
		}, {
			num.abs.do({|idx|
				var lineY = staffBottomY + ((1 + idx) * spaceInterline);
				Pen.strokeColor_(Color.black);
				Pen.width_(1);
				Pen.line(Point(posX - spaceInterline, lineY), Point(posX + spaceInterline, lineY));
				Pen.stroke;
			});
		});
	}

	drawStaff { |offsetStaff = 0, staffClefs|
		var treble = staffClefs.findAll("g").size;
		var bass = staffClefs.findAll("f").size;
		ledgerLinesBounds = List.new; // Each entry: [midiLow, midiHigh, staffTopY, staffBottomY]

		if(treble>1, {
			this.addSingleStaff(-70+offsetStaff, -70+offsetStaff, \trebleClef15);
			ledgerLinesBounds.add([86, 102, staffPosition-70+offsetStaff, staffPosition-70+(4*spaceInterline)+offsetStaff]);
		});
		if(treble>0, {
			this.addSingleStaff(0+offsetStaff, 0+offsetStaff, \trebleClef);
			ledgerLinesBounds.add([62, 80, staffPosition+offsetStaff, staffPosition+(4*spaceInterline)+offsetStaff]);
		});
		if(bass>0, {
			this.addSingleStaff(60+offsetStaff, 40+offsetStaff, \bassClef);
			ledgerLinesBounds.add([41, 59, staffPosition+60+offsetStaff, staffPosition+60+(4*spaceInterline)+offsetStaff]);
		});
		if(bass>1, {
			this.addSingleStaff(130+offsetStaff, 110+offsetStaff, \bassClef15);
			ledgerLinesBounds.add([17, 35, staffPosition+130+offsetStaff, staffPosition+130+(4*spaceInterline)+offsetStaff]);
		});
		ledgerLinesBounds = ledgerLinesBounds.sort({|a,b| a[0] < b[0]}).asArray;
	}

	addSingleStaff {arg offset = 0, clefOffset = 0, clefType = \trebleClef;
		5.do({|v, idx|
			Pen.strokeColor_(Color.black);
			Pen.width_(1);
			Pen.line(
				Point(0, staffPosition+offset+(idx*spaceInterline)),
				Point(staffWidth*scale.reciprocal, staffPosition+offset+(idx*spaceInterline));
			);
			Pen.stroke;
		});
		this.addClef(5, staffPosition-46+clefOffset, clefType);
	}

	addClef {arg posX, posY, clefType = \trebleClef;
		Pen.stringAtPoint(smuflDictionary[clefType], posX@(posY-offsetY+76), Font(musicFont, 38), Color.black;);
	}

	updateCanvasSize {
		var maxBeats = rhythms.collect({ |staffRhythms| staffRhythms.abs.sum }).maxItem;
		var noteOriginX = 80; // same origin used in renderMusic
		var rightMargin = 40;

		staffWidth = windowWidth.max(scale * (noteOriginX + (maxBeats * defaultSpacing) + rightMargin));
		staffHeight = windowHeight.max(staffPosition + (4 * spaceInterline * numStaves) + (distanceInBetweenStaves * numStaves)) * scale;

		if(userView.notNil, {
			userView.bounds_(Rect(0, 0, staffWidth, staffHeight));
		});
	}


	loadSmuflDictionary {
		//needs to be expanded to support 96TET
		accidentalForDeviation = Dictionary[
			-4 -> "",
			-3 -> "",
			-2 -> "",
			-1 -> "",
			0 -> "",
			1 -> "",
			2 -> "",
			3 -> "",
			4 -> "",
			9999 -> "",
		];
		smuflDictionary = Dictionary[
			//noteheads
			\noteShapeRoundBlack -> "",
			\noteShapeDiamondWhite ->"",
			\noteShapeDiamondBlack ->"",
			\textBlackNoteLongStem -> "",
			\noteQuarterDown -> "",

			//clefs
			\trebleClef15 -> "",
			\trebleClef -> "",
			\bassClef -> "",
			\bassClef15 -> "", //careful: looks like there's nothing, but the glyph is there!

			//accidentals
			\flat -> "",
			\sharp -> "",
			\natural -> "",
			\quarterToneFlat -> "",
			\quarterToneSharp -> "",
			\threeQuarterTonesSharp -> "",
			\flatArrowUp -> "",
			\flatArrowDown -> "",
			\sharpArrowUp -> "",
			\sharpArrowDown -> ""
		]
	}

	//export pitches as string for bach: TO BE DONE
	// [6000, [6500, 6900, 7100], 8600]; ---> [6000 [6500 6900 7100] 8600]
	exportPitchesToBach {
		/*
		var length = midiNotes.shape.last;
		var result = "[";
		result = midiNotes.collect({|item, i|
		if(item.shape.isNil, {item, {}})

		});
		rhythmTree.do({|v, i| result = result++v; });
		^result;
		*/
	}


	//draw playback line
	playbackLine { |bottomY|
		var elapsed;
		if(isPlaying and: { isPaused.not }, {
			elapsed = thisThread.seconds - startTimePlayback;
			playbackPoint = playbackOffset + (elapsed * pixelsPerSecond);
			scrollView.visibleOrigin_(Point((playbackPoint - (windowWidth / 2) * scale).max(0), 0));
		}, {
			playbackPoint = playbackOffset;
		});

		Pen.strokeColor_(Color.new255(0, 200, 50));
		Pen.width_(2);
		Pen.line(
			Point(60+playbackPoint, staffPosition-150+bottomY),
			Point(60+playbackPoint, staffPosition-100);
		);
		Pen.line(
			Point(60+playbackPoint, staffPosition-100),
			Point(50+playbackPoint, staffPosition-110);
		);
		Pen.line(
			Point(60+playbackPoint, staffPosition-100),
			Point(70+playbackPoint, staffPosition-110);
		);
		Pen.stroke;
	}

	setBpm {|bpm = 60|
		playbackOffset = playbackOffset + ((thisThread.seconds - startTimePlayback) * pixelsPerSecond);
		startTimePlayback = thisThread.seconds;
		tempoBpm = bpm;
		TempoClock.default.tempo = (tempoBpm/60); //beats/seconds, so 2 = 120 BPM).
		pixelsPerSecond = defaultSpacing * (bpm / 60);
	}

	eazyPlay { |bpm = 60, instrument = \default|
		// reset position
		playbackPoint = 0;
		playbackOffset = 0;
		startTimePlayback = thisThread.seconds;
		isPaused = false;
		isPlaying = true;

		this.setBpm(bpm);
		Server.local.waitForBoot {
			startTimePlayback = thisThread.seconds;
			userView.animate_(true).frameRate_(30); // always 30, never changes
			players = numStaves.collect({ |n|
				var durExpanded = rhythms[n].collect({ |d|
					if(d < 0, {Rest(d.abs)}, {d})
				});
				var pitchExpanded = List.new;
				var position = 0;
				rhythms[n].do({ |d|
					if(d < 0, { pitchExpanded.add(Rest()) },
						{ pitchExpanded.add(midiNotes[n][position]); position = position + 1 })
				});
				("Durations for Pbind:   " ++ durExpanded).postln;
				("Pitches expanded for Pbind: " ++ pitchExpanded.asArray).postln;
				Pbind(
					\instrument, instrument,
					\midinote,   Pseq(pitchExpanded.asArray, 1),
					\dur,        Pseq(durExpanded, 1),
					\amp,        0.5 / numStaves,
					\legato,     0.8
				).play;
			});
		}
	}

	eazyMIDIplay { |bpm = 60, midiPort = 0|
		// reset position
		playbackPoint = 0;
		playbackOffset = 0;
		startTimePlayback = thisThread.seconds;
		isPaused = false;
		isPlaying = true;

		this.setBpm(bpm);
		//MIDIClient.destinations;
		midiOut = MIDIOut(midiPort);

		startTimePlayback = thisThread.seconds;
		userView.animate_(true).frameRate_(30); // always 30, never changes
		players = numStaves.collect({ |n|
			var durExpanded = rhythms[n].collect({ |d|
				if(d < 0, {Rest(d.abs)}, {d})
			});
			var pitchExpanded = List.new;
			var position = 0;
			rhythms[n].do({ |d|
				if(d < 0, { pitchExpanded.add(Rest()) },
					{ pitchExpanded.add(midiNotes[n][position]); position = position + 1 })
			});
			// ("Durations for Pbind:   " ++ durExpanded).postln; // DEBUG
			// ("Pitches expanded for Pbind: " ++ pitchExpanded.asArray).postln; // DEBUG
			Pbind(
				\type,    \midi,
				\midiout, midiOut,
				\midinote,   Pseq(pitchExpanded.asArray, 1),
				\dur,        Pseq(durExpanded, 1),
				\amp,        0.66 / numStaves,
				\sustain,    Pseq(durExpanded, 1)
			).play;
		});

	}

	pause {
		if(isPaused.not) {
			players.do({ |p| p.pause });
			pauseTime = thisThread.seconds;
			// save position at pause moment
			playbackOffset = playbackOffset + ((pauseTime - startTimePlayback) * pixelsPerSecond);
			userView.animate_(false);
			isPaused = true;
		}
	}

	resume {
		if(isPaused) {
			players.do({ |p| p.resume });
			startTimePlayback = thisThread.seconds;
			userView.animate_(true);
			isPaused = false;
		}
	}

	stop {
		players.do({ |p| p.stop });
		userView.animate_(false);
		isPlaying = false;
		isPaused = false;
		playbackPoint = 0;
		playbackOffset = 0;
		scrollView.visibleOrigin_(0@0);
		userView.refresh;
	}

	setFont {|font = "Bravura"|
		musicFont = font;
		if(musicFont== "Bravura", {offsetY = 76});
		if(musicFont== "Petaluma", {offsetY = 38});
		userView.refresh;
	}

	setDefaultSpacing { |value|
		defaultSpacing = value;
		this.updateCanvasSize;
		userView.refresh;
	}

	// update with a new clef
	changeClefs { |newClefs|
		clefs = newClefs.asArray;
		distanceInBetweenStaves = 100*(clefs.maxItem.asString.size);
		if(clefs.size<numStaves, {clefs = clefs ++ (clefs.last!(numStaves-clefs.size))});
		clefs = clefs.collect({|i| i.asString});
		userView.refresh;
	}

	// force show all accidentals
	setForceAccidentals {|boolean|
		forceAccidentals = boolean;
		userView.refresh;
	}

	// set the view scale
	setScale { |value|
		scale = value;
		this.updateCanvasSize;
		userView.refresh;
	}

	// show the duration lines
	setShowDurations {|boolean|
		showDurations = boolean;
		userView.refresh;
	}

	// show or not the stems
	setShowStems {|boolean|
		showStems = boolean;
		userView.refresh;
	}

	// get all the midinotes as array
	getPitches {
		^midiNotes;
	}

	// get all the rhythms as array
	getRhythms {
		^rhythms;
	}

	exportToOM {|fileName|
		fileName = fileName ? windowTitle;
		if (SuperOM.isNil) {
			"SuperOM is missing. Please install from https://github.com/claudiopanariello/SuperOM".throw;
		};

		SuperOM.new.writeOMfile(
			fileName: fileName++".omi",
			midicents: this.getPitches*100, //default SuperOM is in midicents, while SuperScore is in midi
			rhythmTree: this.getRhythms
		)
	}

	// update with new music
	// since a lot of preprocessing is done in .init and not in .renderMusic, I have to re-run a bunch of lines
	// Not sure this is the best way to do it, but anyway, it works for now
	updateMusic { |newPitches, newRhythms, newClefs|
		midiNotes = newPitches ?? { midiNotes };
		rhythms   = newRhythms ?? { rhythms };
		clefs     = (newClefs ?? { clefs }).asArray;
		distanceInBetweenStaves = 100*(clefs.maxItem.asString.size);

		midiNotes = midiNotes.asArray.fixShape;
		numStaves = midiNotes.shape[0];

		if(clefs.size<numStaves, {clefs = clefs ++ (clefs.last!(numStaves-clefs.size))});
		clefs = clefs.collect({|i| i.asString});

		this.fixDurations;
		this.updateCanvasSize;
		scrollView.visibleOrigin_(0@0); //for safety, to bring the scrollView back to the origin

		userView.refresh;
	}

	refresh {
		userView.refresh; // Redraw
	}

	buildGui {
		this.createWindow(windowTitle);
		userView = UserView.new(scrollView, staffWidth@staffHeight)
		.background_(Color.gray(0.9));
		userView.resize = 5;
		userView.drawFunc = { this.renderMusic };
		CmdPeriod.add(this);
	}

	cmdPeriod {
		this.stop;
	}

	show {
		if(window.isNil or: { window.isClosed }, {
			this.buildGui;
		}, {
			window.front;
		});
	}

	free {
		window.close;
	}

	*bachInvention {
		var bach_RH_P, bach_RH_R, bach_LH_R, bach_LH_P;
		// pitches right hand Bach Invention n.1
		bach_RH_P = [60, 62, 64, 65, 62, 64, 60, 67, 72, 71, 69, 71, 72, 74, 67, 69, 71, 72, 69, 71, 67, 74, 79, 77, 76, 77, 79, 76, 81, 79, 77, 76, 79, 77, 81, 79, 77, 76, 74, 72, 76, 74, 77, 76, 74, 72, 71, 69, 72, 71, 74, 72, 71, 69, 67, 66, 69, 67, 71, 69, 62, 72, 71, 72, 74, 71, 69, 67, 66, 64, 67, 66, 69, 67, 71, 69, 72, 71, 74, 72, 76, 74, 71, 72, 74, 79, 71, 72, 71, 69, 67, 67, 67, 69, 71, 72, 69, 71, 67, 66, 69, 71, 72, 74, 71, 72, 69, 71, 74, 72, 71, 69, 72, 71, 74, 72, 76, 74, 72, 71, 74, 73, 76, 74, 73, 74, 76, 77, 69, 71, 73, 74, 66, 68, 69, 71, 72, 74, 64, 66, 68, 69, 66, 68, 64, 76, 74, 72, 76, 74, 72, 71, 74, 72, 81, 80, 83, 81, 76, 77, 74, 68, 77, 76, 74, 72, 74, 72, 71, 69, 69, 81, 79, 77, 76, 79, 77, 81, 79, 76, 77, 79, 81, 77, 79, 76, 77, 79, 77, 76, 74, 77, 76, 79, 77, 74, 76, 77, 79, 76, 77, 74, 76, 72, 74, 76, 77, 74, 76, 72, 74, 76, 77, 79, 81, 77, 79, 76, 77, 79, 81, 83, 84, 81, 83, 79, 84, 79, 76, 77, 76, 74, 72, 72, 70, 69, 67, 65, 69, 67, 70, 69, 71, 72, 64, 62, 72, 65, 71, [ 64, 67, 72 ]];

		// rhythm right hand Bach Invention n.1
		bach_RH_R = [ -1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 2.0, 2.0, 0.5, 0.5, 1.0, 2.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 2.0, 2.0, 0.5, 0.5, 1.0, 2.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 2.0, 2.0, 0.5, 0.5, 2.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 0.5, 0.5, 1.0, 1.0, 0.5, 0.5, 1.0, 1.0, 1.0, 2.0, -7.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 2.0, -7.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 2.0, -7.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 2.0, -7.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 4.0, -1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 0.5, 0.5, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 9, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 9.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 9, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 9, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 2.0, 2.0, 0.5, 0.5, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 16.0]/4;

		// pitches left hand Bach Invention n.1
		bach_LH_P = [ 48, 50, 52, 53, 50, 52, 48, 55, 43, 55, 57, 59, 60, 57, 59, 55, 60, 59, 60, 62, 64, 55, 57, 59, 60, 52, 54, 55, 57, 59, 60, 50, 52, 54, 55, 52, 54, 50, 55, 47, 48, 50, 52, 54, 55, 52, 47, 48, 50, 38, 43, 45, 47, 48, 45, 47, 43, 50, 55, 54, 55, 57, 50, 52, 54, 55, 52, 54, 50, 57, 62, 60, 62, 55, 67, 65, 64, 62, 65, 64, 67, 65, 64, 65, 62, 64, 69, 67, 65, 64, 67, 65, 69, 67, 65, 67, 64, 65, 70, 69, 67, 65, 69, 67, 70, 69, 67, 65, 64, 62, 65, 64, 67, 65, 64, 62, 60, 59, 62, 60, 64, 62, 60, 59, 57, 56, 59, 57, 60, 59, 52, 62, 60, 62, 60, 59, 57, 55, 54, 57, 56, 59, 57, 60, 59, 62, 60, 64, 62, 65, 64, 57, 64, 52, 57, 45, 64, 62, 60, 59, 62, 61, 64, 62, 57, 59, 60, 62, 59, 60, 57, 59, 62, 60, 59, 57, 60, 59, 62, 60, 55, 57, 58, 60, 57, 58, 55, 57, 58, 57, 55, 53, 62, 60, 58, 57, 65, 64, 62, 64, 50, 52, 53, 55, 52, 53, 50, 52, 48, 50, 52, 53, 50, 52, 53, 55, 43, [ 36, 48 ] ];

		// rhythm left hand Bach Invention n.1
		bach_LH_R = [ -9, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 2.0, 2.0, -5.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 5.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 3.0, 1.0, 2.0, 2.0, -1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 2.0, 2.0, 2.0, 2.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 2.0, 2.0, 2.0, 2.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 2.0, 2.0, 2.0, 2.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 2.0, 2.0, 2.0, 2.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 2.0, 2.0, 0.5, 0.5, 3.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, -5.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 9.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 9.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 9.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 2.0, 2.0, 2.0, 2.0, 1.0, 1.0, 1.0, 1.0, 2.0, 2.0, 16.0]/4;

		^this.new([bach_RH_P, bach_LH_P], [bach_RH_R, bach_LH_R], clefs: \fg, windowTitle: "J.S. Bach: Inventions for Two Voices No.1 in C major, BWV 772", defaultSpacing: 100);
	}

}

// Two super simple methods:
+ Array {

	// To get the number of rows of an array. This is because I want numRows(1-dimensional Array) = 1
	numRows {
		var result = this.size;
		if(this.shape[1]==nil, {result = 1;} );
		^result;
	}

	// If I have a 1-dim array, I encapsulate it, so the shape changes from [n] to [1, n]
	fixShape{
		var result = this;
		if(this.shape[1]==nil, {result = [result];} );
		^result;
	}
}
