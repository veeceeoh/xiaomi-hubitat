/**
 *  Xiaomi Aqara Temperature Humidity Sensor Device Driver for Hubitat Elevation hub
 *  Version 0.1b
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
 *  Based on SmartThings device handler code by a4refillpad
 *  With contributions by alecm, alixjg, bspranger, gn0st1c, foz333, jmagnuson, rinkek, ronvandegraaf, snalee, tmleafs, twonk, & veeceeoh
 *  Code reworked for use with Hubitat Elevation hub by veeceeoh
 *
 *  Known issues:
 *  Xiaomi devices do not accept configReporting commmands, but instead send reports based on changes, and a status report every 50-60 minutes
 *  Pairing Xiaomi devices can be difficult as they were not designed to use with a Hubitat hub.
 *
 */

metadata {
  definition (name: "Xiaomi Aqara Temperature Humidity Sensor", namespace: "veeceeoh", author: "veeceeoh") {
	capability "Temperature Measurement"
	capability "Relative Humidity Measurement"
  capability "Sensor"
	capability "Battery"

	attribute "lastCheckin", "String"
	attribute "lastCheckinDate", "String"
	attribute "batteryRuntime", "String"

  fingerprint profileId: "0104", deviceId: "5F01", inClusters: "0000, 0003, FFFF, 0402, 0403, 0405", outClusters: "0000, 0004, FFFF", manufacturer: "LUMI", model: "lumi.weather", deviceJoinName: "Xiaomi Aqara Temp Sensor"

	command "resetBatteryRuntime"
}

  preferences {
		//Temp and Humidity Offsets
    input "tempOffset", "decimal", title:"Temperature Offset", description:"Adjust temperature by this many degrees", range:"*..*"
    input "humidOffset", "number", title:"Humidity Offset", description:"Adjust humidity by this many percent", range: "*..*"
    input "pressOffset", "number", title:"Pressure Offset", description:"Adjust pressure by this many units", range: "*..*"
    input name:"PressureUnits", type:"enum", title:"Pressure Units", options:["mbar", "kPa", "inHg", "mmHg"], description:"Sets the unit in which pressure will be reported"
		//Date & Time Config
    input name: "dateformat", type: "enum", title: "Set Date Format for lastCheckin US (MDY) - UK (DMY) - Other (YMD)", description: "Date Format", options:["US","UK","Other"]
    input name: "clockformat", type: "bool", title: "Use 24 hour clock?"
		//Battery Reset Config
		input name: "battReset", type: "bool", title: "Click this toggle and press save when battery has been replaced", description: ""
		//Battery Voltage Offset
		input description: "Only change the settings below if you know what you're doing.", type: "paragraph", element: "paragraph", title: "ADVANCED SETTINGS"
		input name: "voltsmax", title: "Max Volts (A battery is at 100% at ___ volts, range 2.8 to 3.4)", type: "decimal", range: "2.8..3.4", defaultValue: 3
		input name: "voltsmin", title: "Min Volts (A battery needs replacing at ___ volts, Range 2.0 to 2.7)", type: "decimal", range: "2..2.7", defaultValue: 2.5
	}
}

// Parse incoming device messages to generate events
def parse(String description) {
  // log.debug "${device.displayName}: Parsing description: ${description}"
  def cluster = description.split(",").find {it.split(":")[0].trim() == "cluster"}?.split(":")[1].trim()
  def attrId = description.split(",").find {it.split(":")[0].trim() == "attrId"}?.split(":")[1].trim()
  def valueHex = description.split(",").find {it.split(":")[0].trim() == "value"}?.split(":")[1].trim()

	// Determine current time and date in the user-selected date format and clock style
  def now = formatDate()
  def nowDate = new Date(now).getTime()

	// Any report - temp, humidity, pressure, & battery - results in a lastCheckin event and update to Last Checkin tile
	// However, only a non-parseable report results in lastCheckin being displayed in events log
  sendEvent(name: "lastCheckin", value: now, displayed: false)
  sendEvent(name: "lastCheckinDate", value: nowDate, displayed: false)

  Map map = [:]

	// Send message data to appropriate parsing function based on the type of report
	if (cluster == "0402") {
    map = parseTemperature(valueHex)
  } else if (cluster == "0405") {
    map = parseHumidity(valueHex)
  } else if (cluster == "0403") {
    map = parsePressure(valueHex)
  } else if (cluster == "0000" & attrId == "0005") {
    log.debug "${device.displayName}: Reset button was short-pressed"
	} else if (description?.startsWith('catchall:')) {
		map = parseCatchAllMessage(valueHex)
	} else {
		log.debug "${device.displayName}: was unable to parse ${description}"
    sendEvent(name: "lastCheckin", value: now)
	}

	if (map) {
		log.debug "${device.displayName}: Parse returned ${map}"
		return createEvent(map)
	} else
		return [:]
}

// Calculate temperature with 0.1 precision in C or F unit as set by hub location settings
private parseTemperature(description) {
  float temp = Integer.parseInt(description,16)/100
  //log.debug "${device.displayName}: Raw reported temperature = ${temp}°C"
	def offset = tempOffset ? tempOffset : 0
	temp = (temp > 100) ? (100 - temp) : temp
  temp = (temperatureScale == "F") ? ((temp * 1.8) + 32) + offset : temp + offset
  temp = temp.round(1)
  return [
    name: 'temperature',
    value: temp,
    unit: "${temperatureScale}",
    isStateChange: true,
    descriptionText: "${device.displayName} temperature is ${temp}°${temperatureScale}",
    translatable:true
  ]
}

// Calculate humidity with 0.1 precision
private parseHumidity(description) {
  float humidity = Integer.parseInt(description,16)/100
  //log.debug "${device.displayName}: Raw reported humidity = ${humidity}%"
	humidity = humidityOffset ? (humidity + humidityOffset) : humidity
  humidity = humidity.round(1)
  return [
    name: 'humidity',
    value: humidity,
    unit: "%",
    isStateChange: true,
    descriptionText: "${device.displayName} humidity is ${humidity}%",
  ]
}

// Parse pressure report
private Map parsePressure(description) {
		float pressureval = Integer.parseInt(description, 16)
		if (!(settings.PressureUnits)){
			settings.PressureUnits = "mbar"
		}
		// log.debug "${device.displayName}: Converting ${pressureval} to ${PressureUnits}"
		switch (PressureUnits) {
			case "mbar":
				pressureval = (pressureval/10) as Float
				pressureval = pressureval.round(1);
				break;

			case "kPa":
				pressureval = (pressureval/100) as Float
				pressureval = pressureval.round(2);
				break;

			case "inHg":
				pressureval = (((pressureval/10) as Float) * 0.0295300)
				pressureval = pressureval.round(2);
				break;

			case "mmHg":
				pressureval = (((pressureval/10) as Float) * 0.750062)
				pressureval = pressureval.round(2);
				break;
		}
		// log.debug "${device.displayName}: Pressure is ${pressureval} ${PressureUnits} before applying the pressure offset."
    pressureval = pressOffset ? (pressureval + pressOffset) : pressureval
		pressureval = pressureval.round(2);

		return [
			name: 'pressure',
			value: pressureval,
			unit: "${PressureUnits}",
			isStateChange: true,
			descriptionText : "${device.displayName} Pressure is ${pressureval} ${PressureUnits}"
		]
}

// Check catchall for battery voltage data to pass to getBatteryResult for conversion to percentage report
private Map parseCatchAllMessage(String description) {
	Map resultMap = [:]
	def catchall = zigbee.parse(description)
	log.debug catchall

	if (catchall.clusterId == 0x0000) {
		def MsgLength = catchall.data.size()
		// Xiaomi CatchAll does not have identifiers, first UINT16 is Battery
		if ((catchall.data.get(0) == 0x01 || catchall.data.get(0) == 0x02) && (catchall.data.get(1) == 0xFF)) {
			for (int i = 4; i < (MsgLength-3); i++) {
				if (catchall.data.get(i) == 0x21) { // check the data ID and data type
					// next two bytes are the battery voltage
					resultMap = getBatteryResult((catchall.data.get(i+2)<<8) + catchall.data.get(i+1))
					break
				}
			}
		}
	}
	return resultMap
}

// Convert raw 4 digit integer voltage value into percentage based on minVolts/maxVolts range
private Map getBatteryResult(rawValue) {
    // raw voltage is normally supplied as a 4 digit integer that needs to be divided by 1000
    // but in the case the final zero is dropped then divide by 100 to get actual voltage value
    def rawVolts = rawValue / 1000
    def minVolts
    def maxVolts

    if(voltsmin == null || voltsmin == "")
    	minVolts = 2.5
    else
   	minVolts = voltsmin

    if(voltsmax == null || voltsmax == "")
    	maxVolts = 3.0
    else
	maxVolts = voltsmax

    def pct = (rawVolts - minVolts) / (maxVolts - minVolts)
    def roundedPct = Math.min(100, Math.round(pct * 100))

    def result = [
        name: 'battery',
        value: roundedPct,
        unit: "%",
        isStateChange:true,
        descriptionText : "${device.displayName} raw battery is ${rawVolts}v"
    ]

    return result
}

//Reset the date displayed in Battery Changed tile to current date
def resetBatteryRuntime(paired) {
	def now = formatDate(true)
	def newlyPaired = paired ? " for newly paired sensor" : ""
	sendEvent(name: "batteryRuntime", value: now)
	log.debug "${device.displayName}: Setting Battery Changed to current date${newlyPaired}"
}

// installed() runs just after a sensor is paired using the "Add a Thing" method in the SmartThings mobile app
def installed() {
	if (!batteryRuntime) resetBatteryRuntime(true)
	checkIntervalEvent("installed")
}

// configure() runs after installed() when a sensor is paired
def configure() {
	log.debug "${device.displayName}: configuring"
	if (!batteryRuntime) resetBatteryRuntime(true)
	checkIntervalEvent("configured")
	return
}

// updated() will run twice every time user presses save in preference settings page
def updated() {
		checkIntervalEvent("updated")
		if(battReset){
		resetBatteryRuntime()
		device.updateSetting("battReset", false)
	}
}

private checkIntervalEvent(text) {
    // Device wakes up every 1 hours, this interval allows us to miss one wakeup notification before marking offline
    log.debug "${device.displayName}: Configured health checkInterval when ${text}()"
    sendEvent(name: "checkInterval", value: 2 * 60 * 60 + 2 * 60, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID])
}

def formatDate(batteryReset) {
    def correctedTimezone = ""
    def timeString = clockformat ? "HH:mm:ss" : "h:mm:ss aa"

	// If user's hub timezone is not set, display error messages in log and events log, and set timezone to GMT to avoid errors
    if (!(location.timeZone)) {
        correctedTimezone = TimeZone.getTimeZone("GMT")
        log.error "${device.displayName}: Time Zone not set, so GMT was used. Please set up your location in the SmartThings mobile app."
        sendEvent(name: "error", value: "", descriptionText: "ERROR: Time Zone not set, so GMT was used. Please set up your location in the SmartThings mobile app.")
    }
    else {
        correctedTimezone = location.timeZone
    }

    if (dateformat == "US" || dateformat == "" || dateformat == null) {
        if (batteryReset)
            return new Date().format("MMM dd yyyy", correctedTimezone)
        else
            return new Date().format("EEE MMM dd yyyy ${timeString}", correctedTimezone)
    }
    else if (dateformat == "UK") {
        if (batteryReset)
            return new Date().format("dd MMM yyyy", correctedTimezone)
        else
            return new Date().format("EEE dd MMM yyyy ${timeString}", correctedTimezone)
        }
    else {
        if (batteryReset)
            return new Date().format("yyyy MMM dd", correctedTimezone)
        else
            return new Date().format("EEE yyyy MMM dd ${timeString}", correctedTimezone)
    }
}
