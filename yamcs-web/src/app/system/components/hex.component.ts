import { Component, Input } from '@angular/core';

@Component({
  selector: 'app-hex',
  templateUrl: './hex.component.html'
})
export class HexComponent {

  @Input()
  base64String: string;

  getHexString() {
    const raw = atob(this.base64String);
    let result = '';
    let charCount = 0;
    let lineAscii = '';
    for (let i = 0; i < raw.length; i++) {
      if (i % 16 === 0) {
        const charCountHex = charCount.toString(16);
        result += this.lpad(charCountHex, 8);
        result += ': ';
      }
      const code = raw.charCodeAt(i);
      const hex = code.toString(16);
      if (32 <= code && code <= 126) {
        lineAscii += raw[i];
      } else {
        lineAscii += '.';
      }

      result += (hex.length === 2 ? hex : '0' + hex);
      if ((i + 1) % 2 === 0) {
        result += ' ';
      }

      if ((i + 1) % 16 === 0) {
        result += ' ' + lineAscii + '\n';
        lineAscii = '';
        charCount += 16;
      }
    }
    return result;
  }

  private lpad(hex: string, width: number) {
    if (hex.length >= width) {
      return hex;
    } else {
      return new Array(width - hex.length + 1).join('0') + hex;
    }
  }
}
