/**
 * Copyright 2019 Adubbz
 * Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is hereby granted, provided that the above copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package adubbz.switchloader.common;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;

import adubbz.switchloader.nxo.NXOAdapter;
import adubbz.switchloader.nxo.NXOHeader;
import adubbz.switchloader.nxo.NXOSection;
import adubbz.switchloader.nxo.NXOSectionType;
import adubbz.switchloader.util.UIUtil;
import ghidra.app.cmd.label.SetLabelPrimaryCmd;
import ghidra.app.util.MemoryBlockUtil;
import ghidra.app.util.bin.BinaryReader;
import ghidra.app.util.bin.ByteProvider;
import ghidra.app.util.bin.format.elf.ElfDynamicType;
import ghidra.app.util.bin.format.elf.ElfSectionHeaderConstants;
import ghidra.app.util.bin.format.elf.ElfStringTable;
import ghidra.app.util.bin.format.elf.ElfSymbol;
import ghidra.app.util.bin.format.elf.relocation.AARCH64_ElfRelocationConstants;
import ghidra.app.util.importer.MemoryConflictHandler;
import ghidra.framework.store.LockException;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressOutOfBoundsException;
import ghidra.program.model.address.AddressOverflowException;
import ghidra.program.model.address.AddressSet;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.data.DataTypeConflictException;
import ghidra.program.model.data.TerminatedStringDataType;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Library;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.MemoryAccessException;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.ExternalLocation;
import ghidra.program.model.symbol.ExternalManager;
import ghidra.program.model.symbol.Namespace;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolTable;
import ghidra.program.model.util.CodeUnitInsertionException;
import ghidra.util.Msg;
import ghidra.util.exception.DuplicateNameException;
import ghidra.util.exception.InvalidInputException;
import ghidra.util.exception.NotFoundException;
import ghidra.util.task.TaskMonitor;

public abstract class NXProgramBuilder 
{
    protected ByteProvider fileByteProvider;
    protected Program program;
    protected NXOHeader nxo;
    protected MemoryBlockUtil mbu;
    
    protected AddressSpace aSpace;
    protected MemoryBlockHelper memBlockHelper;
    
    protected List<PltEntry> pltEntries = new ArrayList<>();
    protected int undefSymbolCount;
    
    protected NXProgramBuilder(Program program, ByteProvider provider, NXOAdapter adapter, MemoryConflictHandler handler)
    {
        this.program = program;
        this.fileByteProvider = provider;
        this.nxo = new NXOHeader(adapter, 0x7100000000L);
        this.mbu = new MemoryBlockUtil(program, handler);
    }
    
    protected void load(TaskMonitor monitor)
    {
        NXOAdapter adapter = this.nxo.getAdapter();
        ByteProvider memoryProvider = adapter.getMemoryProvider();
        this.aSpace = program.getAddressFactory().getDefaultAddressSpace();
        
        try 
        {
            // Set the base address
            this.program.setImageBase(aSpace.getAddress(this.nxo.getBaseAddress()), true);
            this.memBlockHelper = new MemoryBlockHelper(monitor, this.program, memoryProvider, this.mbu, this.nxo.getBaseAddress());
            
            NXOSection text = adapter.getSection(NXOSectionType.TEXT);
            NXOSection rodata = adapter.getSection(NXOSectionType.RODATA);
            NXOSection data = adapter.getSection(NXOSectionType.DATA);
            
            // Setup memory blocks
            InputStream textInputStream = memoryProvider.getInputStream(text.getOffset());
            InputStream rodataInputStream = memoryProvider.getInputStream(rodata.getOffset());
            InputStream dataInputStream = memoryProvider.getInputStream(data.getOffset());
            
            this.memBlockHelper.addDeferredSection(".text", text.getOffset(), textInputStream, text.getSize(), true, false, true);
            this.memBlockHelper.addDeferredSection(".rodata", rodata.getOffset(), rodataInputStream, rodata.getSize(), true, false, false);
            this.memBlockHelper.addDeferredSection(".data", data.getOffset(), dataInputStream, data.getSize(), true, true, false);
            
            if (adapter.getMOD0() == null)
            {
                // We can't create .dynamic, so work with what we've got.
                this.memBlockHelper.finalizeSections();
                return;
            }
            
            this.memBlockHelper.addSection(".dynamic", adapter.getMOD0().getDynamicOffset(), memoryProvider.getInputStream(adapter.getMOD0().getDynamicOffset()), this.nxo.getDynamicTable().getLength(), true, true, false);

            // Create dynamic sections
            this.optionallyCreateDynBlock(".dynstr", ElfDynamicType.DT_STRTAB, ElfDynamicType.DT_STRSZ);
            this.optionallyCreateDynBlock(".init_array", ElfDynamicType.DT_INIT_ARRAY, ElfDynamicType.DT_INIT_ARRAYSZ);
            this.optionallyCreateDynBlock(".fini_array", ElfDynamicType.DT_FINI_ARRAY, ElfDynamicType.DT_FINI_ARRAYSZ);
            this.optionallyCreateDynBlock(".rela.dyn", ElfDynamicType.DT_RELA, ElfDynamicType.DT_RELASZ);
            this.optionallyCreateDynBlock(".rel.dyn", ElfDynamicType.DT_REL, ElfDynamicType.DT_RELSZ);
            this.optionallyCreateDynBlock(".rela.plt", ElfDynamicType.DT_JMPREL, ElfDynamicType.DT_PLTRELSZ);
            
            this.memBlockHelper.addSection(".dynsym", this.nxo.getSymbolTable().getFileOffset(), memoryProvider.getInputStream(this.nxo.getSymbolTable().getFileOffset()), this.nxo.getSymbolTable().getLength(), true, false, false);

            this.setupStringTable();
            this.setupSymbolTable();
            this.setupRelocations();
            this.memBlockHelper.finalizeSections();
            this.setupImports(monitor);
            this.performRelocations();
            
            // Create BSS
            this.mbu.createUninitializedBlock(false, ".bss", aSpace.getAddress(this.nxo.getBaseAddress() + adapter.getMOD0().getBssStartOffset()), adapter.getMOD0().getBssSize(), "", null, true, true, false);
        }
        catch (IOException | NotFoundException | AddressOverflowException | AddressOutOfBoundsException | CodeUnitInsertionException | DataTypeConflictException | MemoryAccessException | InvalidInputException | LockException e)
        {
            e.printStackTrace();
        }
        
        // Ensure memory blocks are ordered from first to last.
        // Normally they are ordered by the order they are added.
        UIUtil.sortProgramTree(this.program);
    }
    
    protected void setupStringTable() throws AddressOverflowException, CodeUnitInsertionException, DataTypeConflictException
    {
       ElfStringTable stringTable = this.nxo.getStringTable();
       long stringTableAddrOffset = stringTable.getAddressOffset();
        
        Address address = this.aSpace.getAddress(stringTableAddrOffset);
        Address end = address.addNoWrap(stringTable.getLength() - 1);
        
        while (address.compareTo(end) < 0) 
        {
            int length = this.createString(address);
            address = address.addNoWrap(length);
        }
    }
    
    protected void setupSymbolTable()
    {
        for (ElfSymbol elfSymbol : this.nxo.getSymbolTable().getSymbols()) 
        {
            String symName = elfSymbol.getNameAsString();

            if (elfSymbol.getSectionHeaderIndex() == ElfSectionHeaderConstants.SHN_UNDEF && symName != null && !symName.isEmpty())
            {
                // NOTE: We handle adding these symbols later
                this.undefSymbolCount++;
            }
            else
            {
                Address address = this.aSpace.getAddress(this.nxo.getBaseAddress() + elfSymbol.getValue());
                this.evaluateElfSymbol(elfSymbol, address, false);
            }
        }
    }
    
    protected void setupRelocations() throws AddressOverflowException, AddressOutOfBoundsException, IOException, NotFoundException
    {
        NXOAdapter adapter = this.nxo.getAdapter();
        ByteProvider memoryProvider = adapter.getMemoryProvider();
        BinaryReader memoryReader = adapter.getMemoryReader();
        ImmutableList<NXRelocation> pltRelocs = this.nxo.getPltRelocations();
        
        if (pltRelocs.isEmpty())
        {
            Msg.info(this, "No plt relocations found.");
            return;
        }
            
        long pltGotStart = pltRelocs.get(0).offset;
        long pltGotEnd = pltRelocs.get(pltRelocs.size() - 1).offset + 8;
        
        if (this.nxo.getDynamicTable().containsDynamicValue(ElfDynamicType.DT_PLTGOT))
        {
            long pltGotOff = this.nxo.getDynamicTable().getDynamicValue(ElfDynamicType.DT_PLTGOT);
            this.memBlockHelper.addSection(".got.plt", pltGotOff, memoryProvider.getInputStream(pltGotOff), pltGotEnd - pltGotStart, true, false, false);
        }
        
        int last = 12;
        
        while (true)
        {
            int pos = -1;
            
            for (int i = last; i < adapter.getSection(NXOSectionType.TEXT).getSize(); i++)
            {
                if (memoryReader.readInt(i) == 0xD61F0220)
                {
                    pos = i;
                    break;
                }
            }
            
            if (pos == -1) break;
            last = pos + 1;
            if ((pos % 4) != 0) continue;
            
            int off = pos - 12;
            long a = Integer.toUnsignedLong(memoryReader.readInt(off));
            long b = Integer.toUnsignedLong(memoryReader.readInt(off + 4));
            long c = Integer.toUnsignedLong(memoryReader.readInt(off + 8));
            long d = Integer.toUnsignedLong(memoryReader.readInt(off + 12));

            if (d == 0xD61F0220L && (a & 0x9f00001fL) == 0x90000010L && (b & 0xffe003ffL) == 0xf9400211L)
            {
                long base = off & ~0xFFFL;
                long immhi = (a >> 5) & 0x7ffffL;
                long immlo = (a >> 29) & 3;
                long paddr = base + ((immlo << 12) | (immhi << 14));
                long poff = ((b >> 10) & 0xfffL) << 3;
                long target = paddr + poff;
                if (pltGotStart <= target && target < pltGotEnd)
                    this.pltEntries.add(new PltEntry(off, target));
            }
        }
        
        long pltStart = this.pltEntries.get(0).off;
        long pltEnd = this.pltEntries.get(this.pltEntries.size() - 1).off + 0x10;
        this.memBlockHelper.addSection(".plt", pltStart, memoryProvider.getInputStream(pltStart), pltEnd - pltStart, true, false, false);
        
        boolean good = false;
        long gotEnd = pltGotEnd + 8;
        
        while (!this.nxo.getDynamicTable().containsDynamicValue(ElfDynamicType.DT_INIT_ARRAY) || gotEnd < this.nxo.getDynamicTable().getDynamicValue(ElfDynamicType.DT_INIT_ARRAY))
        {
            boolean foundOffset = false;
            
            for (NXRelocation reloc : this.nxo.getRelocations())
            {
                if (reloc.offset == gotEnd)
                {
                    foundOffset = true;
                    break;
                }
            }
            
            if (!foundOffset)
                break;
            
            good = true;
            gotEnd += 8;
        }
        
        if (good)
            this.memBlockHelper.addSection(".got", pltGotEnd, memoryProvider.getInputStream(pltGotEnd), gotEnd - pltGotEnd, true, false, false);
    }
    
    protected void performRelocations() throws MemoryAccessException, InvalidInputException, AddressOutOfBoundsException
    {
        Map<Long, String> gotNameLookup = new HashMap<>(); 
        
        // Relocations again
        for (NXRelocation reloc : this.nxo.getRelocations()) 
        {
            Address target = this.aSpace.getAddress(reloc.offset + this.nxo.getBaseAddress());
            
            if (reloc.r_type == AARCH64_ElfRelocationConstants.R_AARCH64_GLOB_DAT ||
                reloc.r_type == AARCH64_ElfRelocationConstants.R_AARCH64_JUMP_SLOT ||
                reloc.r_type == AARCH64_ElfRelocationConstants.R_AARCH64_ABS64) 
            {
                if (reloc.sym == null) 
                {
                    // Ignore these sorts of errors, the IDA loader fails on some relocations too.
                    // It doesn't appear to be a Ghidra specific issue.
                    //Msg.error(this, String.format("Error: Relocation at %x failed", target.getOffset()));
                } 
                else 
                {
                    program.getMemory().setLong(target, reloc.sym.getValue() + this.nxo.getBaseAddress() + reloc.addend);
                    
                    if (reloc.addend == 0)
                        gotNameLookup.put(reloc.offset, reloc.sym.getNameAsString());
                }
            } 
            else if (reloc.r_type == AARCH64_ElfRelocationConstants.R_AARCH64_RELATIVE) 
            {
                program.getMemory().setLong(target, this.nxo.getBaseAddress() + reloc.addend);
            } 
            else 
            {
                Msg.info(this, String.format("TODO: r_type 0x%x", reloc.r_type));
            }
        }
        
        for (PltEntry entry : this.pltEntries)
        {
            if (gotNameLookup.containsKey(entry.target))
            {
                long addr = this.nxo.getBaseAddress() + entry.off;
                String name = gotNameLookup.get(entry.target);
                // TODO: Mark as func
                this.createSymbol(this.aSpace.getAddress(addr), name, false, false, null);
            }
        }
    }
    
    protected void setupImports(TaskMonitor monitor)
    {
        this.processImports(monitor);
        
        if (this.undefSymbolCount == 0)
            return;
        
        // Create the fake EXTERNAL block after everything else
        long lastAddrOff = this.nxo.getBaseAddress(); 
        
        for (MemoryBlock block : this.program.getMemory().getBlocks())
        {
            if (block.getEnd().getOffset() > lastAddrOff)
                lastAddrOff = block.getEnd().getOffset();
        }
        
        int undefEntrySize = 1; // We create fake 1 byte functions for imports
        long externalBlockAddrOffset = ((lastAddrOff + 0xFFF) & ~0xFFF) + undefEntrySize; // plus 1 so we don't end up on the "end" symbol
        
        // Create the block where imports will be located
        this.createExternalBlock(this.aSpace.getAddress(externalBlockAddrOffset), this.undefSymbolCount * undefEntrySize);
        
        // Handle imported symbols
        for (ElfSymbol elfSymbol : this.nxo.getSymbolTable().getSymbols())
        {
            String symName = elfSymbol.getNameAsString();
            
            if (elfSymbol.getSectionHeaderIndex() == ElfSectionHeaderConstants.SHN_UNDEF && symName != null && !symName.isEmpty())
            {
                Address address = this.aSpace.getAddress(externalBlockAddrOffset);
                elfSymbol.setValue(externalBlockAddrOffset); // Fix the value to be non-zero, instead pointing to our fake EXTERNAL block
                this.evaluateElfSymbol(elfSymbol, address, true);
                externalBlockAddrOffset += undefEntrySize;
            }
        }
    }
    
    private void createExternalBlock(Address addr, long size) 
    {
        try 
        {
            MemoryBlock block = this.program.getMemory().createUninitializedBlock("EXTERNAL", addr, size, false);

            // assume any value in external is writable.
            block.setWrite(true);
            block.setSourceName("Switch Loader");
            block.setComment("NOTE: This block is artificial and is used to make relocations work correctly");
        }
        catch (Exception e) 
        {
            Msg.error(this, "Error creating external memory block: " + " - " + e.getMessage());
        }
    }
    
    private void processImports(TaskMonitor monitor) 
    {
        if (monitor.isCancelled())
            return;

        monitor.setMessage("Processing imports...");

        ExternalManager extManager = program.getExternalManager();
        String[] neededLibs = this.nxo.getDynamicLibraryNames();
        
        for (String neededLib : neededLibs) 
        {
            try {
                extManager.setExternalPath(neededLib, null, false);
            }
            catch (InvalidInputException e) {
                Msg.error(this, "Bad library name: " + neededLib);
            }
        }
    }
    
    protected Address createEntryFunction(String name, long entryAddr, TaskMonitor monitor) 
    {
        Address entryAddress = this.aSpace.getAddress(entryAddr);

        // TODO: Entry may refer to a pointer - make sure we have execute permission
        MemoryBlock block = this.program.getMemory().getBlock(entryAddress);
        
        if (block == null || !block.isExecute()) 
        {
            return entryAddress;
        }

        Function function = program.getFunctionManager().getFunctionAt(entryAddress);
        
        if (function != null) 
        {
            program.getSymbolTable().addExternalEntryPoint(entryAddress);
            return entryAddress; // symbol-based function already created
        }

        try 
        {
            this.createOneByteFunction(name, entryAddress, true);
        }
        catch (Exception e) 
        {
            Msg.error(this, "Could not create symbol at entry point: " + e);
        }

        return entryAddress;
    }
    
    protected int createString(Address address) throws CodeUnitInsertionException, DataTypeConflictException 
    {
        Data d = this.program.getListing().getDataAt(address);
        
        if (d == null || !TerminatedStringDataType.dataType.isEquivalent(d.getDataType())) 
        {
            d = this.program.getListing().createData(address, TerminatedStringDataType.dataType, -1);
        }
        
        return d.getLength();
    }
    
    private void evaluateElfSymbol(ElfSymbol elfSymbol, Address address, boolean isFakeExternal)
    {
        try
        {
            if (elfSymbol.isSection()) {
                // Do not add section symbols to program symbol table
                return;
            }
    
            String name = elfSymbol.getNameAsString();
            if (name == null) {
                return;
            }
    
            boolean isPrimary = (elfSymbol.getType() == ElfSymbol.STT_FUNC) ||
                (elfSymbol.getType() == ElfSymbol.STT_OBJECT) || (elfSymbol.getSize() != 0);
            // don't displace existing primary unless symbol is a function or object symbol
            if (name.contains("@")) {
                isPrimary = false; // do not make version symbol primary
            }
            else if (!isPrimary && (elfSymbol.isGlobal() || elfSymbol.isWeak())) {
                Symbol existingSym = program.getSymbolTable().getPrimarySymbol(address);
                isPrimary = (existingSym == null);
            }
    
            this.createSymbol(address, name, isPrimary, elfSymbol.isAbsolute(), null);
    
            // NOTE: treat weak symbols as global so that other programs may link to them.
            // In the future, we may want additional symbol flags to denote the distinction
            if ((elfSymbol.isGlobal() || elfSymbol.isWeak()) && !isFakeExternal) 
            {
                program.getSymbolTable().addExternalEntryPoint(address);
            }
            
            if (elfSymbol.getType() == ElfSymbol.STT_FUNC) 
            {
                Function existingFunction = program.getFunctionManager().getFunctionAt(address);
                if (existingFunction == null) {
                    Function f = createOneByteFunction(null, address, false);
                    if (f != null) {
                        if (isFakeExternal && !f.isThunk()) {
                            ExternalLocation extLoc = program.getExternalManager().addExtFunction(Library.UNKNOWN, name, null, SourceType.IMPORTED);
                            f.setThunkedFunction(extLoc.getFunction());
                            // revert thunk function symbol to default source
                            Symbol s = f.getSymbol();
                            if (s.getSource() != SourceType.DEFAULT) {
                                program.getSymbolTable().removeSymbolSpecial(f.getSymbol());
                            }
                        }
                    }
                }
            }
        }
        catch (DuplicateNameException | InvalidInputException e)
        {
            e.printStackTrace();
        }
    }
    
    public Function createOneByteFunction(String name, Address address, boolean isEntry) 
    {
        Function function = null;
        try 
        {
            FunctionManager functionMgr = program.getFunctionManager();
            function = functionMgr.getFunctionAt(address);
            if (function == null) {
                function = functionMgr.createFunction(null, address, new AddressSet(address), SourceType.IMPORTED);
            }
        }
        catch (Exception e) 
        {
            Msg.error(this, "Error while creating function at " + address + ": " + e.getMessage());
        }

        try 
        {
            if (name != null) 
            {
                createSymbol(address, name, true, false, null);
            }
            if (isEntry) {
                program.getSymbolTable().addExternalEntryPoint(address);
            }
        }
        catch (Exception e) {
            Msg.error(this, "Error while creating symbol " + name + " at " + address + ": " + e.getMessage());
        }
        return function;
    }
    
    public Symbol createSymbol(Address addr, String name, boolean isPrimary, boolean pinAbsolute, Namespace namespace) throws InvalidInputException 
    {
        // TODO: At this point, we should be marking as data or code
        SymbolTable symbolTable = program.getSymbolTable();
        Symbol sym = symbolTable.createLabel(addr, name, namespace, SourceType.IMPORTED);
        if (isPrimary) {
            checkPrimary(sym);
        }
        if (pinAbsolute && !sym.isPinned()) {
            sym.setPinned(true);
        }
        return sym;
    }
    
    private Symbol checkPrimary(Symbol sym) 
    {
        if (sym == null || sym.isPrimary()) 
        {
            return sym;
        }

        String name = sym.getName();
        Address addr = sym.getAddress();

        if (name.indexOf("@") > 0) { // <sym>@<version> or <sym>@@<version>
            return sym; // do not make versioned symbols primary
        }

        // if starts with a $, probably a markup symbol, like $t,$a,$d
        if (name.startsWith("$")) {
            return sym;
        }

        // if sym starts with a non-letter give preference to an existing symbol which does
        if (!Character.isAlphabetic(name.codePointAt(0))) {
            Symbol primarySymbol = program.getSymbolTable().getPrimarySymbol(addr);
            if (primarySymbol != null && primarySymbol.getSource() != SourceType.DEFAULT &&
                Character.isAlphabetic(primarySymbol.getName().codePointAt(0))) {
                return sym;
            }
        }

        SetLabelPrimaryCmd cmd = new SetLabelPrimaryCmd(addr, name, sym.getParentNamespace());
        if (cmd.applyTo(program)) {
            return program.getSymbolTable().getSymbol(name, addr, sym.getParentNamespace());
        }

        Msg.error(this, cmd.getStatusMsg());

        return sym;
    }
    
    protected void optionallyCreateDynBlock(String name, ElfDynamicType offsetType, ElfDynamicType sizeType) throws NotFoundException, IOException, AddressOverflowException, AddressOutOfBoundsException
    {
        if (this.nxo.getDynamicTable().containsDynamicValue(offsetType) && this.nxo.getDynamicTable().containsDynamicValue(sizeType))
        {
            long offset = this.nxo.getDynamicTable().getDynamicValue(offsetType);
            long size = this.nxo.getDynamicTable().getDynamicValue(sizeType);
            
            if (size > 0)
            {
                Msg.info(this, String.format("Created dyn block %s at 0x%X of size 0x%X", name, offset, size));
                this.memBlockHelper.addSection(name, offset, this.nxo.getAdapter().getMemoryProvider().getInputStream(offset), size, true, false, false);
            }
        }
    }
    
    private static class PltEntry
    {
        long off;
        long target;
        
        public PltEntry(long offset, long target)
        {
            this.off = offset;
            this.target = target;
        }
    }
}