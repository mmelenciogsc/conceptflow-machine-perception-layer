/* SPDX-License-Identifier: MIT OR Apache-2.0 */
/* One-way migration adding the explicit regular/world/relative beacon mode parameter. */
function fail(message){throw new Error("[MPL_FMOD_BEACON_UPGRADE] "+message);}
function requireValue(value,message){if(!value)fail(message);return value;}
if(studio.version.productVersion!==2||studio.version.majorVersion!==3||studio.version.minorVersion!==14)
    fail("FMOD Studio 2.03.14 is required");
var focused=requireValue(studio.project.lookup("event:/MachinePerception/AuditoryIcons/FocusedObject"),"Missing FocusedObject event");
var existing=focused.getParameterPresets().filter(function(item){return item.presetOwner.name==="BeaconMode";});
if(existing.length!==0) fail("BeaconMode already exists; migration must run exactly once");
var beaconMode=requireValue(studio.project.workspace.addGameParameter({
    name:"BeaconMode",type:studio.project.parameterType.UserDiscrete,min:0,max:3
}),"Could not create BeaconMode");
var proxy=requireValue(focused.addGameParameter(beaconMode),"Could not attach BeaconMode"); proxy.initialValue=0;
focused.note="At most one focused-object or spatial-beacon auditory icon. BeaconMode 0 is ordinary focus, 1 is a measured WORLD anchor, and 2 is an orientation-stabilized relative bearing whose origin follows the listener. Supplemental awareness only.";
requireValue(studio.project.save(),"Could not save project");
console.log("[MPL_FMOD_BEACON_UPGRADE] status=Created BeaconMode=0..2 focusedObjectMax=1");
