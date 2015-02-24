# org.blom.martin.usb #

Random USB support code in Java. At the moment, just a HID Report
Descriptor parser which can be used to decode (and encode) raw HID
messages.

Javadoc [here](http://leviticusmb.github.io/org.blom.martin.usb/org.blom.martin.usb-1.0.0/javadoc/index.html).

## Build and test ##

```sh
$ ./gradlew installApp
$ build/install/org.blom.martin.usb/bin/org.blom.martin.usb 05010902A1010901A100050919012903150025019503750181029501750581010501093009311581257F750895028106C0C0
```

