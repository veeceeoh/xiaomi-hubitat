/**
 *  Xiaomi Mi Cube Controller - model MFKZQ01LM
 *  Device Driver for Hubitat Elevation hub
 *  Version 0.2.1b
 *
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Based on original SmartThings device handler code by Oleg "DroidSector" Smirnov and Artur Draga
 *  With contributions by alecm, alixjg, bspranger, gn0st1c, foz333, jmagnuson, rinkek, ronvandegraaf, snalee, tmleafs, twonk, & veeceeoh
 *  Reworked and additional code for use with Hubitat Elevation hub by veeceeoh
 *
 *  Known issues:
 *  + Xiaomi devices send reports based on changes, and a status report every 50-60 minutes. These settings cannot be adjusted.
 *  + The battery level / voltage is not reported at pairing. Wait for the first status report, 50-60 minutes after pairing.
 *  + Pairing Xiaomi devices can be difficult as they were not designed to use with a Hubitat hub.
 *    Holding the Cube's reset button (inside, next to the battery) until the LED blinks will start pairing mode.
 *    3 quick flashes indicates success, while one long flash means pairing has not started yet.
 *    In either case, keep the sensor "awake" by short-pressing the reset button repeatedly, until recognized by Hubitat.
 *  + The connection can be dropped without warning. To reconnect, put Hubitat in "Discover Devices" mode, and follow
 *    the same steps for pairing. As long as it has not been removed from the Hubitat's device list, when the LED
 *    flashes 3 times, the sensor should be reconnected and will resume reporting as normal
 *
 */

metadata {
	definition (name: "Xiaomi Mi Cube Controller", namespace: "veeceeoh", author: "veeceeoh") {
		capability "Actuator"
		capability "PushableButton"
		capability "Configuration"
		capability "Battery"
		capability "Three Axis" //Simulated!
		capability "Sensor"

		attribute "face", "number"
		attribute "angle", "number"
		attribute "lastCheckin", "String"
		attribute "batteryLastReplaced", "String"
		attribute "buttonPressed", "String"
		attribute "buttonHeld", "String"
		attribute "buttonReleased", "String"

		// Fingerprint data taken from ZiGate webpage http://zigate.fr/xiaomi-magic-cube-cluster
		fingerprint endpointId: "01", profileId: "0104", deviceId: "5F01", inClusters: "0000, 0003, 0012, 0019", outClusters: "0000, 0003, 0012, 0019", manufacturer: "LUMI", model: "lumi.sensor_cube"
		fingerprint endpointId: "01", inClusters: "0000, 0003, 0012, 0019", outClusters: "0000, 0003, 0012, 0019", manufacturer: "LUMI", model: "lumi.sensor_cube"
		fingerprint endpointId: "01", profileId: "0104", deviceId: "5F01", inClusters: "0000, 0003, 0019", outClusters: "0000, 0003, 0019", manufacturer: "LUMI", model: "lumi.sensor_cube"
		fingerprint endpointId: "01", inClusters: "0000, 0003, 0019", outClusters: "0000, 0003, 0019", manufacturer: "LUMI", model: "lumi.sensor_cube"
		fingerprint profileId: "0104", deviceId: "5F01", inClusters: "0000, 0003, 0012, 0019", outClusters: "0000, 0003, 0012, 0019", manufacturer: "LUMI", model: "lumi.sensor_cube"

		command "setFace0"
		command "setFace1"
		command "setFace2"
		command "setFace3"
		command "setFace4"
		command "setFace5"

		command "flip90"
		command "flip180"
		command "slide"
		command "knock"
		command "rotateR"
		command "rotateL"
		command "shake"

		command "enrollResponse"
		command "resetBatteryReplacedDate"
	}

	preferences {
		input (name: "cubeMode", title: "Cube Mode: Select how many buttons to control", description: "", type: "enum", options: [0: "Simple - 7 buttons", 1: "Advanced - 36 buttons", 2: "Combined - 43 buttons"])
		input name: "voltsmin", title: "Min Volts (0% battery = ___ volts, range 2.0 to 2.7)", description: "Default = 2.5 Volts", type: "decimal", range: "2..2.7"
		input name: "voltsmax", title: "Max Volts (100% battery = ___ volts, range 2.8 to 3.4)", description: "Default = 3.0 Volts", type: "decimal", range: "2.8..3.4"
		//Logging Message Config
		input name: "infoLogging", type: "bool", title: "Enable info message logging", description: ""
		input name: "debugLogging", type: "bool", title: "Enable debug message logging", description: ""
		//Firmware 2.0.5 Compatibility Fix Config
		input name: "oldFirmware", type: "bool", title: "DISABLE 2.0.5 firmware compatibility fix (for users of 2.0.4 or earlier)", description: ""
	}
}

def setFace0() { setFace(0) }
def setFace1() { setFace(1) }
def setFace2() { setFace(2) }
def setFace3() { setFace(3) }
def setFace4() { setFace(4) }
def setFace5() { setFace(5) }
def flip90() {
	def flipMap = [0:5, 1:2, 2:0, 3:2, 4:5, 5:3]
	flipEvents(flipMap[device.currentValue("face") as Integer], "90")
}
def flip180() {
	def flipMap = [0:3, 1:4, 2:5, 3:0, 4:1, 5:2]
	flipEvents(flipMap[device.currentValue("face") as Integer], "180")
}
def rotateL() { rotateEvents(-90) }
def rotateR() { rotateEvents(90) }
def slide() { slideEvents(device.currentValue("face") as Integer) }
def knock() { knockEvents(device.currentValue("face") as Integer) }
def shake() { shakeEvents() }

def setFace(Integer faceId) {
	def Integer prevFaceId = device.currentValue("face")
	if (prevFaceId == faceId) {
		flipEvents(faceId, "0")
	} else if ((prevFaceId == 0 && faceId == 3)||(prevFaceId == 1 && faceId == 4)||(prevFaceId == 2 && faceId == 5)||(prevFaceId == 3 && faceId == 0)||(prevFaceId == 4 && faceId == 1)||(prevFaceId == 5 && faceId == 2)){
		flipEvents(faceId, "180")
	} else {
		flipEvents(faceId, "90")
	}
}

// Parse incoming device messages to generate events
def parse(String description) {
	def cluster = description.split(",").find {it.split(":")[0].trim() == "cluster"}?.split(":")[1].trim()
	def attrId = description.split(",").find {it.split(":")[0].trim() == "attrId"}?.split(":")[1].trim()
	def valueHex = description.split(",").find {it.split(":")[0].trim() == "value"}?.split(":")[1].trim()
	displayDebugLog("Parsing description: ${description}")
	Map map = [:]

	if (!oldFirmware & valueHex)
		// Reverse order of bytes in description's value hex string - required for Hubitat firmware 2.0.5 or newer
		valueHex = reverseHexString(valueHex)

	// lastCheckin can be used with webCoRE
	sendEvent(name: "lastCheckin", value: now())

	displayDebugLog("Parsing message: ${description}")

	// Send message data to appropriate parsing function based on the type of report
	if (cluster == "0006") {
		map = parseButtonMessage(Integer.parseInt(valueHex))
	} else if (cluster == "0000" & attrId == "0005") {
		displayInfoLog("Reset button was short-pressed")
		// Parse battery level from longer type of announcement message
		map = (valueHex.size() > 60) ? parseBattery(valueHex.split('FF42')[1]) : [:]
	} else if (cluster == "0000" & (attrId == "FF01" || attrId == "FF02")) {
		// Parse battery level from hourly announcement message
		map = (valueHex.size() > 30) ? parseBattery(valueHex) : [:]
	} else if (!(cluster == "0000" & attrId == "0001")) {
		displayDebugLog("Unable to parse message")
	}

	if (description?.startsWith('catchall:')) {
		parseCatchAllMessage(description)
	}
	else if (description?.startsWith('read attr -')) {
		parseReportAttributeMessage(description)
	}

	if (map != [:]) {
		displayDebugLog("Creating event $map")
		return createEvent(map)
	} else
		return [:]
}

// Reverses order of bytes in hex string
def reverseHexString(hexString) {
	def reversed = ""
	for (int i = hexString.length(); i > 0; i -= 2) {
		swaped += hexString.substring(i - 2, i )
	}
	return reversed
}

// Convert raw 4 digit integer voltage value into percentage based on minVolts/maxVolts range
private parseBattery(description) {
	displayDebugLog("Battery parse string = ${description}")
	def MsgLength = description.size()
	def rawValue
	for (int i = 4; i < (MsgLength-3); i+=2) {
		if (description[i..(i+1)] == "21") { // Search for byte preceeding battery voltage bytes
			rawValue = Integer.parseInt((description[(i+4)..(i+5)] + description[(i+2)..(i+3)]),16)
			break
		}
	}
	def rawVolts = rawValue / 1000
	def minVolts = voltsmin ? voltsmin : 2.5
	def maxVolts = voltsmax ? voltsmax : 3.0
	def pct = (rawVolts - minVolts) / (maxVolts - minVolts)
	def roundedPct = Math.min(100, Math.round(pct * 100))
	def descText = "Battery level is ${roundedPct}% (${rawVolts} Volts)"
	displayInfoLog(descText)
	def result = [
		name: 'battery',
		value: roundedPct,
		unit: "%",
		isStateChange: true,
		descriptionText: descText
	]
	return result
}

private Map parseReportAttributeMessage(String description) {
	Map descMap = (description - "read attr - ").split(",").inject([:]) {
		map, param ->
		def nameAndValue = param.split(":")
		map += [(nameAndValue[0].trim()):nameAndValue[1].trim()]
	}
	displayDebugLog("Cluster: ${descMap.cluster}, Attribute ID: ${descMap.attrId}, Value: ${descMap.value}")
	if (descMap.cluster == "0012" && descMap.attrId == "0055") { // Shake, flip, knock, slide
		displayInfoLog("Shake, flip, knock, or slide detected")
		getMotionResult(descMap.value)
	} else if (descMap.cluster == "000C" && descMap.attrId == "ff05") { // Rotation (90 and 180 degrees)
		displayInfoLog("Rotation detected")
		getRotationResult(descMap.value)
	} else {
		displayDebugLog("Unknown Cluster / Attribute ID")
	}
}

def String hexToBin(String thisByte, Integer size = 8) {
	String binaryValue = new BigInteger(thisByte, 16).toString(2);
	return String.format("%${size}s", binaryValue).replace(' ', '0')
}

private Map getMotionResult(String value) {
	String motionType = value[0..1]
	String binaryValue = hexToBin(value[2..3])
	Integer sourceFace = Integer.parseInt(binaryValue[2..4],2)
	Integer targetFace = Integer.parseInt(binaryValue[5..7],2)
	log.debug "motionType: ${motionType}, binaryValue: ${binaryValue}, sourceFace: ${sourceFace}, targetFace: ${targetFace}"
	if (motionType == "00") {
		switch(binaryValue[0..1]) {
			case "00":
				if (targetFace==0) { shakeEvents() }
				break
			case "01":
				flipEvents(targetFace, "90")
				break
			case "10":
				flipEvents(targetFace, "180")
				break
		}
	} else if (motionType == "01") {
		slideEvents(targetFace)
	} else if (motionType == "02") {
		knockEvents(targetFace)
	}
}

private Map getRotationResult(value) {
	Integer angle = Math.round(Float.intBitsToFloat(Long.parseLong(value[0..7],16).intValue()));
	rotateEvents(angle)
}

def Map shakeEvents() {
	if (!settings.cubeMode || settings.cubeMode in ['0','2'] ) {
		sendEvent([
			name: "pushed",
			value: 1,
			data: [face: device.currentValue("face")],
			descriptionText: (settings.cubeMode == '0') ? "$device.displayName was shaken" : null,
			isStateChange: true,
		])
	}

	if (settings.cubeMode in ['1','2'] ){
		sendEvent([
			name: "pushed",
			value: (device.currentValue("face") as Integer) + ((settings.cubeMode == '1') ? 31 : 38),
			data: [face: device.currentValue("face")],
			descriptionText: "$device.displayName was shaken (Face # ${device.currentValue("face")}).",
			isStateChange: true])
	}
}

def flipEvents(Integer faceId, String flipType) {
	if (flipType == "0") {
		sendEvent( [name: 'face', value: -1, isStateChange: false] )
		sendEvent( [name: 'face', value: faceId, isStateChange: false] )
	} else if (flipType == "90") {
		if (settings.cubeMode in ['0','2']) {
			sendEvent( [
				name: "pushed",
				value: 2 ,
				data: [face: faceId],
				descriptionText: (settings.cubeMode == '0') ? "$device.displayName detected $flipType degree flip" : null,
				isStateChange: true,
			] )
		}
	} else if (flipType == "180") {
		if (settings.cubeMode in ['0','2']) {
			sendEvent( [
				name: "pushed",
				value: 3 ,
				data: [face: faceId],
				descriptionText: (settings.cubeMode == '0') ? "$device.displayName detected $flipType degree flip" : null,
				isStateChange: true,
			] )
		}
	}
	sendEvent( [name: 'face', value: faceId, isStateChange: true, displayed: false ] )
	if (settings.cubeMode in ['1','2']) {
		sendEvent( [
			name: "pushed",
			value: faceId+((settings.cubeMode == '1') ? 1 : 8),
			data: [face: faceId],
			descriptionText: "$device.displayName was fliped to face # $faceId",
			isStateChange: true
	   ] )
	}
	switch (faceId) {
		case 0: sendEvent( [ name: "threeAxis", value: "0,-1000,0", isStateChange: true, displayed: false] ); break
		case 1: sendEvent( [ name: "threeAxis", value: "-1000,0,0", isStateChange: true, displayed: false] ); break
		case 2: sendEvent( [ name: "threeAxis", value: "0,0,1000", isStateChange: true, displayed: false] ); break
		case 3: sendEvent( [ name: "threeAxis", value: "1000,0,0", isStateChange: true, displayed: false] ); break
		case 4: sendEvent( [ name: "threeAxis", value: "0,1000,0", isStateChange: true, displayed: false] ); break
		case 5: sendEvent( [ name: "threeAxis", value: "0,0,-1000", isStateChange: true, displayed: false] ); break
	}
}

def Map slideEvents(Integer targetFace) {
	if ( targetFace != device.currentValue("face") as Integer ) { log.info "Stale face data, updating."; setFace(targetFace) }
	if (!settings.cubeMode || settings.cubeMode in ['0','2'] ) {
		sendEvent( [
			name: "pushed",
			value: 4,
			data: [face: targetFace],
			descriptionText: (settings.cubeMode == '0') ? "$device.displayName detected slide motion." : null,
			isStateChange: true,
		]  )
	}

	if ( settings.cubeMode in ['1','2'] ) {
		sendEvent( [
			name: "pushed",
			value: targetFace+((settings.cubeMode == '1') ? 7 : 14),
			data: [face: targetFace],
			descriptionText: "$device.displayName was slid with face # $targetFace up.",
			isStateChange: true
		] ) }
}

def knockEvents(Integer targetFace) {
	if ( targetFace != device.currentValue("face") as Integer ) { log.info "Stale face data, updating."; setFace(targetFace) }
	if (!settings.cubeMode || settings.cubeMode in ['0','2'] ) {
		sendEvent( [
			name: "pushed",
			value: 5,
			data: [face: targetFace],
			descriptionText: (settings.cubeMode == '0') ? "$device.displayName detected knock motion." : null,
			isStateChange: true,
		] )
	}
	if ( settings.cubeMode in ['1','2'] ) {
		sendEvent( [
			name: "pushed",
			value: targetFace+((settings.cubeMode == '1') ? 13 : 20),
			data: [face: targetFace],
			descriptionText: "$device.displayName was knocked with face # $targetFace up",
			isStateChange: true
		] )
	 }
}

def rotateEvents(Integer angle) {
	sendEvent( [ name: "angle", value: angle, isStateChange: true, displayed: false] )
	if ( angle > 0 ) {
		if (!settings.cubeMode || settings.cubeMode in ['0','2'] ) {
			sendEvent( [
				name: "pushed",
				value: 6,
				data: [face: device.currentValue("face"), angle: angle],
				descriptionText: (settings.cubeMode == '0') ? "$device.displayName was rotated right." : null,
				isStateChange: true,
			] )
		}
		if ( settings.cubeMode in ['1','2'] ) {
			sendEvent( [
				name: "pushed",
				value: (device.currentValue("face") as Integer) + ((settings.cubeMode == '1') ? 19 : 26),
				data: [face: device.currentValue("face")],
				descriptionText: "$device.displayName was rotated right (Face # ${device.currentValue("face")}).",
				isStateChange: true
			] )
		}
	} else {
		if (!settings.cubeMode || settings.cubeMode in ['0','2'] ) {
			sendEvent( [
				name: "pushed",
				value: 7,
				data: [face: device.currentValue("face"), angle: angle],
				descriptionText: (settings.cubeMode == '0') ? "$device.displayName was rotated left." : null,
				isStateChange: true,
			] )
		}
		if ( settings.cubeMode in ['1','2'] ) {
			sendEvent( [
				name: "pushed",
				value: (device.currentValue("face") as Integer) + ((settings.cubeMode == '1') ? 25 : 32),
				data: [face: device.currentValue("face")],
				descriptionText: "$device.displayName was rotated left (Face # ${device.currentValue("face")}).",
				isStateChange: true
			] )
		}
	}
}

private def displayDebugLog(message) {
	if (debugLogging) log.debug "${device.displayName}: ${message}"
}

private def displayInfoLog(message) {
	if (infoLogging || state.prefsSetCount != 1)
		log.info "${device.displayName}: ${message}"
}

//Reset the batteryLastReplaced date to current date
def resetBatteryReplacedDate(paired) {
	def newlyPaired = paired ? " for newly paired sensor" : ""
	sendEvent(name: "batteryLastReplaced", value: new Date())
	displayInfoLog("Setting Battery Last Replaced to current date${newlyPaired}")
}

// this call is here to avoid Groovy errors when the Push command is used
// it is empty because the Xioami button is non-controllable
def push() {
	displayDebugLog("No action taken on Push Command. This button cannot be controlled.")
}

// this call is here to avoid Groovy errors when the Hold command is used
// it is empty because the Xioami button is non-controllable
def hold() {
	displayDebugLog("No action taken on Hold Command. This button cannot be controlled!")
}

// installed() runs just after a device is paired
def installed() {
	state.prefsSetCount = 0
	displayInfoLog("Installing")
	numButtons()
}

// configure() runs after installed() when a device is paired or reconnected
def configure() {
	displayInfoLog("Configuring")
	if (!device.currentState('batteryLastReplaced')?.value)
		resetBatteryReplacedDate(true)
	numButtons()
	displayInfoLog("Number of buttons = ${device.currentState('numberOfButtons')?.value}")
	state.prefsSetCount = 1
	return
}
// updated() runs every time user saves preferences
def updated() {
	displayInfoLog("Updating preference settings")
	if (!device.currentState('batteryLastReplaced')?.value)
		resetBatteryReplacedDate(true)
	numButtons()
	displayInfoLog("Number of buttons = ${device.currentState('numberOfButtons')?.value}")
	displayInfoLog("Info message logging enabled")
	displayDebugLog("Debug message logging enabled")
}

// Set number of buttons available to Apps based on user setting
def numButtons() {
	if (state.lastUpdated && (now() - state.lastUpdated) < 500)
		return
	switch(settings.cubeMode) {
		case "1": sendEvent(name: "numberOfButtons", value: 36); break
		case "2": sendEvent(name: "numberOfButtons", value: 43); break
		default: sendEvent(name: "numberOfButtons", value: 7); break
	}
	state.lastUpdated = now()
}
