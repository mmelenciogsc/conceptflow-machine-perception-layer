/* SPDX-License-Identifier: MIT OR Apache-2.0 */
function fail(message){throw new Error("[MPL_FMOD_VALIDATE] "+message);}
function requireCondition(condition,message){if(!condition)fail(message);}
function lookup(path){var value=studio.project.lookup(path);requireCondition(!!value,"Missing "+path);return value;}
function sources(event){return event.groupTracks[0].mixerGroup.effectChain.effects.filter(function(item){return item.entity==="PluginEffect"&&item.plugin.identifier==="Resonance Audio Source";});}
function parameterByName(event,name){var matches=event.getParameterPresets().filter(function(item){return item.presetOwner.name===name;});requireCondition(matches.length===1,"Expected one parameter "+name);return matches[0];}
function requireCurve(automatable,parameter,expectedPoints,label){
    requireCondition(automatable.automators.length===1,label+" requires one automator");
    var curves=automatable.automators[0].automationCurves;
    requireCondition(curves.length===1,label+" requires one automation curve");
    requireCondition(!!curves[0].parameter&&curves[0].parameter.id===parameter.id,label+" is bound to the wrong parameter");
    var points=curves[0].automationPoints.slice().sort(function(left,right){return left.position-right.position;});
    requireCondition(points.length===expectedPoints.length,label+" point count invalid");
    expectedPoints.forEach(function(expected,index){requireCondition(Math.abs(points[index].position-expected[0])<0.000001&&Math.abs(points[index].value-expected[1])<0.000001,label+" point "+index+" invalid");});
}
requireCondition(studio.version.productVersion===2&&studio.version.majorVersion===3&&studio.version.minorVersion===14,"FMOD Studio 2.03.14 required");
var anchor=lookup("event:/MachinePerception/SoundBubble/IntrusionAnchor"); var field=lookup("event:/MachinePerception/SoundBubble/EnvelopmentField");
var bank=lookup("bank:/MachinePerception"); var bubble=lookup("bus:/Accessible Sonification/Sound Bubble"); var accessible=lookup("bus:/Accessible Sonification");
requireCondition(anchor.is3D()&&field.is3D(),"Events must be 3D"); requireCondition(anchor.groupTracks.length===1&&field.groupTracks.length===1,"Each event requires one layer track");
requireCondition(anchor.groupTracks[0].mixerGroup.name==="Intrusion Anchor"&&field.groupTracks[0].mixerGroup.name==="Envelopment Field","Layer names differ");
requireCondition(anchor.mixerInput.output.id===bubble.id&&field.mixerInput.output.id===bubble.id,"Events must route to Sound Bubble bus"); requireCondition(bubble.output.id===accessible.id,"Bus hierarchy invalid");
requireCondition(anchor.banks.length===1&&anchor.banks[0].id===bank.id&&field.banks.length===1&&field.banks[0].id===bank.id,"Bank assignment invalid");
requireCondition(anchor.masterTrack.mixerGroup.maxInstances===2&&field.masterTrack.mixerGroup.maxInstances===6,"Voice limits invalid");
var anchorSources=sources(anchor); var fieldSources=sources(field);
requireCondition(anchorSources.length===1&&fieldSources.length===1,"Each event requires one Resonance source");
requireCurve(anchor.groupTracks[0].mixerGroup,parameterByName(anchor,"BubbleProximity"),[[0,-12],[.5,-10],[1,-9]],"Anchor level");
requireCurve(field.groupTracks[0].mixerGroup,parameterByName(field,"BubbleProximity"),[[0,-30],[.5,-22],[1,-14]],"Field level");
var fieldSpread=fieldSources[0].plugin.pluginParameters.filter(function(item){return item.name==="Spread";})[0];
requireCondition(!!fieldSpread,"Missing field Spread parameter");
requireCurve(fieldSpread,parameterByName(field,"Envelopment"),[[0,12],[.4,55],[1,135]],"Field spread");
requireCondition(studio.project.model.PluginEffect.findInstances().filter(function(item){return item.plugin.identifier==="Resonance Audio Listener";}).length===1,"Expected one Resonance listener");
requireCondition(studio.project.model.LimiterEffect.findInstances().length===1,"Expected one limiter");
requireCondition(studio.project.model.AudioFile.findInstances().length===2&&studio.project.model.SingleSound.findInstances().length===2,"Expected two original procedural assets and sounds");
requireCondition(studio.project.model.Event.findInstances().length===2,"Unexpected events remain");
console.log("[MPL_FMOD_VALIDATE] status=Pass events=2 anchorVoices=2 fieldVoices=6 limiter=True resonanceSources=2 banksNotRedistributed=True perceptualValidation=False");
