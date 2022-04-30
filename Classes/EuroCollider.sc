/*
TODO

* check if audio interface has DC offset
* add freq2cv(synth) to simple number?
* add cv2freq
* add check if clock stopped via a routine?
* allow to update ClockOut?

---

BUGS

* clockin sometimes does not match?
* clockout: update synth instead of respawning 2
* ensure that we do not overwrite synthdef on e.g. naming of SendReply
*/

EuroSynth {
	// private variables
	var <soundIn;
	var <cvOut;
	var <trigOut;

	var <curFreq;
	var oscFunc;
	var <synth;
	var tuner;
	var <isTuned;
	var randSeed;
	var <tuningMap;
	var tunerOscChannel;
	var tunerDefName;

	*new {|soundIn, cvOut, trigOut|
		^super.newCopyArgs(
			soundIn,
			cvOut,
			trigOut,
		).init;
	}

	*initClass {
		StartUp.add({
			EuroSynth.addEventType;
		});
	}

	init {
		// create a random channel to exchange OSC messages
		// collision is possible but hopefully not occuring
		randSeed = 10000000.rand;

		tunerDefName = "EuroColiderTuner_%".format(randSeed).asSymbol;
		tunerOscChannel = "/EuroCollider/tuner/%".format(randSeed);

		tuningMap = ();
		isTuned = false;

		synth = SynthDef(\EuroColliderSynth, {|cvOut, trigOut, dcOffset=0, t_gate=0|
			var env = EnvGen.ar(Env.perc(0.001, 0.1), gate: t_gate);
			Out.ar(cvOut, DC.ar(1.0)*dcOffset);
			Out.ar(trigOut, env);
		}).play;
	}

	addTuner {
		SynthDef(tunerDefName, {|in, cvOut, dcOffset=0|
			var freq;
			var hasFreq;

			var soundIn = SoundIn.ar(in);
			#freq, hasFreq = Pitch.kr(soundIn, minFreq: 25, median: 10);
			SendReply.ar(
				trig: Pulse.ar(10.0),
				cmdName: tunerOscChannel,
				values: [freq, hasFreq],
			);
			Out.ar(cvOut, DC.ar(1.0) * dcOffset);
		}).add;
	}

	*addEventType {
		Event.addEventType(\euro, {
			var euroSynth = ~euro.value;
			if((euroSynth.class == EuroSynth).not, {
				^"Add a tuned EuroSynth as \"euro\" parameter to you event".postln;
			});
			/*
			if(euroSynth.synth.isRunning.not, {
				^"Please revive synth".postln;
			});
			*/
			euroSynth.synth.set(
				\dcOffset, euroSynth.freqCv(~freq.value).postln;
			);
			euroSynth.synth.set(
				\t_gate, 1.0,
			);
		});
	}

	tune { |baseFreq=55, steps=40, endRange=0.5, negativeSteps=0, minMatch=0.5|
		var routine;

		if(tuner.notNil) {
			"Can only run one tuning process at a time".postln;
			^this;
		};

		oscFunc = OSCFunc({|msg|
			curFreq = msg[3];
		}, path: tunerOscChannel);

		tuner = SynthDef(tunerDefName, {|in, cvOut, dcOffset=0|
			var freq;
			var hasFreq;

			var soundIn = SoundIn.ar(in);
			#freq, hasFreq = Pitch.kr(soundIn, minFreq: 25, median: 10);
			SendReply.ar(
				trig: Pulse.ar(10.0),
				cmdName: tunerOscChannel,
				values: [freq, hasFreq],
			);
			Out.ar(cvOut, DC.ar(1.0) * dcOffset);
		}).play(args: [
			\in, soundIn,
			\cvOut, cvOut,
		]);


		routine = Routine({
			"Start tuning of %".format(this).postln;
			// tune within one quarter tone (default)
			// and from below
			while({(baseFreq.cpsmidi - (curFreq ? 10).cpsmidi).inRange(0.0, minMatch).not}, {
				// todo make this properly
				"pleaseTune: %".format((baseFreq - (curFreq?0))).postln;
				0.1.wait;
			});

			"Absolute pitch is tuned, start relative tuning".postln;

			((negativeSteps.neg..steps)/(steps*(endRange.reciprocal))).do({|i|
				tuner.set(\dcOffset, i);
				0.5.wait;
				tuningMap[i] = curFreq;
				"% \tVolt: \t% Hz".format(i, curFreq).postln;
			});
			"Finished Tuning";
			tuner.set(\dcOffset, 0.0);
			oscFunc.clear;
			tuner.free;
			tuner = nil;
			isTuned = true;
		}).play;
	}

	resetTuning {
		tuningMap = ();
		isTuned = false;
	}

	freqCv {|freq|
		// todo check if is tuned!!!
		// get 2 closest values in tuning array
		var tArray = tuningMap.asSortedArray;
		var tFreqs = tArray.collect({|i| i[1]});
		var tCvs = tArray.collect({|i| i[0]});
		var fDistances = tFreqs - freq;
		// search for closest indices
		var closestIs = (0..fDistances.size-1).sort({|a, b|
			fDistances.abs[a]<fDistances.abs[b];
		});
		// calculate w = (f-b)/(a-b)
		// where w is how much we need mix a and b to get f
		// which we will use to calculate the proper cv mix
		var omega = (freq-tFreqs[closestIs[1]])/(tFreqs[closestIs[0]] - tFreqs[closestIs[1]]);
		// now calculate cv = a*w + b(1-w)
		var cv = (tCvs[closestIs[0]] * omega) + (tCvs[closestIs[1]] * (1-omega));
		^cv;
	}
}

EuroClockIn {
	var in;
	var mean;
	var threshold;

	var oscChannel;
	var oscFunc;
	var synth;
	var <timeStamps;
	var <clock;

	*new { |in, mean=4, threshold=0.3|
		^super.newCopyArgs(
			in,
			mean,
			threshold,
		).init;
	}

	init {
		clock = TempoClock();
		oscChannel = "/EuroCollider/EuroClockIn/%".format(in);
		timeStamps = [];

		oscFunc = OSCFunc({|msg, time|
			this.prInsertTimeStamp(time);
			this.prUpdateClockTempo;
		}, oscChannel);

		SynthDef(\EuroClockIn, {|inBus, threshold|
			// use LocalIn to get a delay of one server buffer
			var soundIn = SoundIn.ar(inBus);
			var localIn = LocalIn.ar(1);
			var localOut = LocalOut.ar(DelayN.ar(soundIn, 0.05, 0.05));
			SendReply.ar(
				(SoundIn.ar(inBus)>=threshold) * (localIn<threshold),
				cmdName: oscChannel,
			);
			Silent.ar;
		}).add;

		Server.default.makeBundle(
			Server.default.latency,
			{synth=Synth(\EuroClockIn, [
				\inBus, in,
				\threshold, threshold,
			])}
		);
	}

	prInsertTimeStamp {|timeStamp|
		if(timeStamps.size >= mean, {
			timeStamps.removeAt(0);
		});
		timeStamps = timeStamps.add(timeStamp);
	}

	prCalculateTempo {
		var tempo = 1.0;
		if(timeStamps.size<2, {
			"Need at least 2 values before we can calculate a tempo".warn;
		}, {
			tempo = (0..timeStamps.size-2).collect({|i|
				timeStamps[i+1] - timeStamps[i];
			}).mean;
		});
		^tempo;
	}

	prUpdateClockTempo {
		if(timeStamps.size >= mean, {
			var curTempo =
			clock.setTempoAtSec(
				this.prCalculateTempo.reciprocal,
				timeStamps.reverse[0],
			);
		}, {
			"Waiting for % beats as input (only received %)".format(
				mean,
				timeStamps.size,
			).postln;
		});
	}
}

EuroClockOut {
	var clock;
	var out;
	var resolution;
	var <synth;

	*new { |clock, out, resolution=1|
		^super.newCopyArgs(
			clock,
			out,
			resolution,
		).init;
	}

	init {
		var routine;

		synth = Synth(\EuroColliderClockOut, [
			\out, out,
		]);

		routine = Routine({
			inf.do({
				"update clock %".format(resolution).postln;
				Server.default.makeBundle(
					time: Server.default.latency,
					func: {
						synth.set(\t_trig, 1);
					},
				);
				resolution.reciprocal.wait;
			});
		});
		clock.schedAbs(clock.elapsedBeats.roundUp(1), routine);
	}

	*initClass {
		StartUp.add({
			EuroClockOut.buildSynthDef;
		});
	}

	*buildSynthDef {
		SynthDef(\EuroColliderClockOut, {|out, t_trig|
			var env = EnvGen.ar(Env.perc(0.001, 0.1), gate: t_trig);
			Out.ar(out, env);
		}).add;
	}
}
