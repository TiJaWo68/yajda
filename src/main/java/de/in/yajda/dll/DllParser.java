package de.in.yajda.dll;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal PE export table reader that extracts exported symbol names. It attempts to parse the PE headers and export directory. If types/signatures are not discoverable from the binary,
 * parameter/return types are marked as "unknown".
 *
 * NOTE: This is a compact implementation for MVP and not a full PE parser.
 */
public class DllParser {

	public static class FunctionInfo {
		public final String name;
		public final String returnType;
		public final List<String> paramTypes;

		public FunctionInfo(String name, String returnType, List<String> paramTypes) {
			this.name = name;
			this.returnType = returnType;
			this.paramTypes = paramTypes;
		}
	}

	public List<FunctionInfo> parseExports(File dll) throws IOException {
		try (RandomAccessFile raf = new RandomAccessFile(dll, "r")) {
			byte[] mz = new byte[2];
			raf.readFully(mz);
			if (mz[0] != 'M' || mz[1] != 'Z') {
				throw new IOException("Not a PE file (MZ header missing)");
			}
			raf.seek(0x3C);
			int e_lfanew = readIntLE(raf);
			raf.seek(e_lfanew);
			int ntSig = readIntLE(raf);
			if (ntSig != 0x00004550) { // "PE\0\0"
				throw new IOException("Invalid PE signature");
			}
			// FileHeader: 20 bytes after signature
			raf.skipBytes(20);
			// OptionalHeader starts here; read Magic to determine 32/64
			int magic = readUnsignedShortLE(raf);
			boolean is64 = (magic == 0x20B);
			// Move to DataDirectory: in Optional Header, DataDirectory starts at offset:
			// For PE32+: offset 112 from start of OptionalHeader to DataDirectory[0]
			// For PE32: offset 96
			int dataDirOffset = is64 ? (int) (raf.getFilePointer() + (112 - 2)) : (int) (raf.getFilePointer() + (96 - 2));
			// we already read 2 bytes (magic), so adjust
			raf.seek(dataDirOffset);
			// Export table directory is first entry (8 bytes): VirtualAddress (rva) and Size
			int exportRVA = readIntLE(raf);
			int exportSize = readIntLE(raf);
			if (exportRVA == 0) {
				// No export table
				return new ArrayList<>();
			}

			// Read section headers to translate RVA -> file offset
			// Seek to NumberOfSections in FileHeader (we skipped it earlier but we didn't store it)
			// Simpler approach: re-read NumberOfSections from FileHeader
			raf.seek(e_lfanew + 4);
			int numberOfSections = readUnsignedShortLE(raf);
			// Skip sizeOfOptionalHeader and characteristics (2 + 2)
			raf.skipBytes(12);
			// Now at start of OptionalHeader again; skip it to come to Section Headers.
			// Read SizeOfOptionalHeader (we need it)
			raf.seek(e_lfanew + 20);
			int sizeOfOptionalHeader = readUnsignedShortLE(raf);
			long sectionHeadersStart = e_lfanew + 24 + sizeOfOptionalHeader;
			// Read sections
			raf.seek(sectionHeadersStart);
			Section[] sections = new Section[numberOfSections];
			for (int i = 0; i < numberOfSections; i++) {
				byte[] nameBytes = new byte[8];
				raf.readFully(nameBytes);
				String name = new String(nameBytes).trim();
				int virtualSize = readIntLE(raf);
				int virtualAddress = readIntLE(raf);
				int sizeOfRawData = readIntLE(raf);
				int pointerToRawData = readIntLE(raf);
				// skip rest of section header (16 bytes)
				raf.skipBytes(16);
				sections[i] = new Section(name, virtualAddress, virtualSize, pointerToRawData, sizeOfRawData);
			}

			long exportOffset = rvaToOffset(exportRVA, sections);
			if (exportOffset <= 0) {
				return new ArrayList<>();
			}
			raf.seek(exportOffset);
			// IMAGE_EXPORT_DIRECTORY is 40 bytes; fields we need:
			// DWORD Characteristics; DWORD TimeDateStamp; WORD MajorVersion; WORD MinorVersion;
			// DWORD Name; DWORD Base; DWORD NumberOfFunctions; DWORD NumberOfNames;
			// DWORD AddressOfFunctions; DWORD AddressOfNames; DWORD AddressOfNameOrdinals;
			raf.skipBytes(8); // Characteristics + TimeDateStamp
			raf.skipBytes(4); // Major/Minor combined or skip
			int nameRVA = readIntLE(raf);
			int base = readIntLE(raf);
			int numberOfFunctions = readIntLE(raf);
			int numberOfNames = readIntLE(raf);
			int addrOfFunctionsRVA = readIntLE(raf);
			int addrOfNamesRVA = readIntLE(raf);
			int addrOfNameOrdinalsRVA = readIntLE(raf);

			long addrOfNamesOffset = rvaToOffset(addrOfNamesRVA, sections);
			long addrOfNameOrdinalsOffset = rvaToOffset(addrOfNameOrdinalsRVA, sections);
			long addrOfFunctionsOffset = rvaToOffset(addrOfFunctionsRVA, sections);

			List<FunctionInfo> result = new ArrayList<>();
			if (addrOfNamesOffset <= 0)
				return result;

			// Read name RVAs
			raf.seek(addrOfNamesOffset);
			int[] nameRVAs = new int[numberOfNames];
			for (int i = 0; i < numberOfNames; i++) {
				nameRVAs[i] = readIntLE(raf);
			}
			// Read ordinals
			raf.seek(addrOfNameOrdinalsOffset);
			int[] ordinals = new int[numberOfNames];
			for (int i = 0; i < numberOfNames; i++) {
				ordinals[i] = readUnsignedShortLE(raf);
			}

			for (int i = 0; i < numberOfNames; i++) {
				int nRva = nameRVAs[i];
				long nameOffset = rvaToOffset(nRva, sections);
				if (nameOffset <= 0)
					continue;
				raf.seek(nameOffset);
				String name = readNullTerminatedString(raf);
				// Parameter and return type discovery not available from export table.
				// Mark as unknown
				List<String> params = new ArrayList<>();
				// Heuristic: if name contains '@' or decorated stdcall patterns, try extract arg byte count -> unknown parameters
				if (name.contains("@") || name.matches(".*\\@\\d+$")) {
					// Could parse ordinal arg bytes but mapping to types is complex. Placeholder.
					params.add("unknown");
				}
				result.add(new FunctionInfo(name, "unknown", params));
			}
			return result;
		}
	}

	private static class Section {
		final String name;
		final int virtualAddress;
		final int virtualSize;
		final int pointerToRawData;
		final int sizeOfRawData;

		Section(String name, int virtualAddress, int virtualSize, int pointerToRawData, int sizeOfRawData) {
			this.name = name;
			this.virtualAddress = virtualAddress;
			this.virtualSize = virtualSize;
			this.pointerToRawData = pointerToRawData;
			this.sizeOfRawData = sizeOfRawData;
		}
	}

	private static long rvaToOffset(int rva, Section[] sections) {
		for (Section s : sections) {
			if (rva >= s.virtualAddress && rva < s.virtualAddress + Math.max(s.virtualSize, s.sizeOfRawData)) {
				int delta = rva - s.virtualAddress;
				return s.pointerToRawData + delta;
			}
		}
		return -1;
	}

	private static int readIntLE(RandomAccessFile raf) throws IOException {
		byte[] b = new byte[4];
		raf.readFully(b);
		ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
		return bb.getInt();
	}

	private static int readUnsignedShortLE(RandomAccessFile raf) throws IOException {
		byte[] b = new byte[2];
		raf.readFully(b);
		ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
		return bb.getShort() & 0xFFFF;
	}

	private static String readNullTerminatedString(RandomAccessFile raf) throws IOException {
		StringBuilder sb = new StringBuilder();
		int c;
		while ((c = raf.read()) > 0) {
			sb.append((char) c);
		}
		return sb.toString();
	}
}