package ca.l1nna.ghidra;

import java.io.File;
import java.io.IOException;
import java.io.FileOutputStream;
import java.io.BufferedOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import java.util.zip.GZIPOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.hash.Hashing;

import ca.l1nna.ghidra.Model.Binary;
import ca.l1nna.ghidra.Model.Block;
import ca.l1nna.ghidra.Model.Comment;
import ca.l1nna.ghidra.Model.Func;
import ca.l1nna.ghidra.Model.FuncSrc;
import ca.l1nna.ghidra.Model.Ins;
import generic.stl.Pair;
import ghidra.GhidraJarApplicationLayout;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.util.bin.ByteProvider;
import ghidra.base.project.GhidraProject;
import ghidra.framework.Application;
import ghidra.framework.ApplicationConfiguration;
import ghidra.framework.HeadlessGhidraApplicationConfiguration;
import ghidra.framework.Platform;
import ghidra.program.flatapi.FlatProgramAPI;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressIterator;
import ghidra.program.model.block.BasicBlockModel;
import ghidra.program.model.block.CodeBlock;
import ghidra.program.model.block.CodeBlockIterator;
import ghidra.program.model.block.CodeBlockReference;
import ghidra.program.model.block.CodeBlockReferenceIterator;
import ghidra.program.model.listing.CodeUnit;
import ghidra.program.model.listing.CodeUnitFormat;
import ghidra.program.model.listing.CodeUnitFormatOptions;
import ghidra.program.model.listing.CodeUnitIterator;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.RefType;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolTable;
import ghidra.test.TestProgramManager;
import ghidra.util.InvalidNameException;
import ghidra.util.exception.CancelledException;
import ghidra.util.exception.DuplicateNameException;
import ghidra.util.exception.VersionException;
import ghidra.util.task.TaskMonitor;

import ghidra.app.util.bin.MemoryByteProvider;
import ghidra.app.util.bin.format.pe.OptionalHeader;
import ghidra.app.util.bin.format.pe.PortableExecutable;
import generic.continues.RethrowContinuesFactory;
import ghidra.program.model.address.AddressFactory;

import ghidra.app.util.XReferenceUtil;

public class GhidraDecompiler {
    private File binaryFile = null;
    private Program program = null;
    private GhidraProject project = null;
    private TestProgramManager manager = null;
    private static final SimpleDateFormat date_formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    private BasicBlockModel basicBlockModel = null;
    private FunctionManager functionManager = null;
    private TaskMonitor monitor = TaskMonitor.DUMMY;
    private CodeUnitFormat format = new CodeUnitFormat(new CodeUnitFormatOptions());
    private DecompInterface decomp = null;
    private boolean decompiled;

    GhidraDecompiler(String binPath, String projPath, boolean decompiled)
            throws IOException, VersionException, CancelledException, DuplicateNameException, InvalidNameException {

        this.binaryFile = new File(binPath);
        this.decompiled = decompiled;
        manager = new TestProgramManager();

        // Initialize application
        if (!Application.isInitialized()) {
            ApplicationConfiguration conf = new HeadlessGhidraApplicationConfiguration();
            conf.setScriptLogFile(null);
            conf.setApplicationLogFile(null);
            conf.setInitializeLogging(false);
            // noisy
            Application.initializeApplication(new GhidraJarApplicationLayout(), conf);
        }

        // Create a Ghidra project
        project = GhidraProject.createProject(projPath, "TempProject", true);
        program = project.importProgram(this.binaryFile);
        basicBlockModel = new BasicBlockModel(program);
        functionManager = program.getFunctionManager();
        GhidraProject.analyze(program);


        FlatProgramAPI fapi = new FlatProgramAPI(program);
        Listing listing = program.getListing();

        // force analysis
        for (MemoryBlock b : program.getMemory().getBlocks()) {
            if(b.isExecute()){
                Address start = b.getStart();
                Address end = b.getEnd();
                while(start.getOffset() <= end.getOffset()){
                    if(!listing.isInFunction(start))
                        fapi.createFunction(
                            start, "NEW_" + Long.toHexString(start.getOffset()));
                    start = start.next();
                }
            }
        }

        if (decompiled) {
            decomp = new DecompInterface();
            decomp.openProgram(program);
        }
    }

    public void close() {
        manager.release(program);
        project.close();
    }

    public void dump(String file) {
        try {

            Model model = new Model();

            Binary bin = new Binary();

            SymbolTable sm = program.getSymbolTable();
            HashSet<String> modules = new HashSet<>();
            for (Symbol s : sm.getExternalSymbols()) {
                String ord = null;
                String module_name = "<EXTERNAL>";
                if (s.getParentSymbol() != null)
                    module_name = s.getParentSymbol().getName().toLowerCase();
                modules.add(module_name);
                long ea = s.getAddress().getOffset();
                if (s.getName().contains("Ordinal_"))
                    ord = s.getName().replace("Ordinal_", "");
                List<String> info = Arrays.asList(module_name, s.getName(), ord);
                bin.import_functions.put(ea, info);

                for (Reference ref : s.getReferences()) {
                    if (ref.getReferenceType() == RefType.DATA)
                        bin.import_functions.put(ref.getFromAddress().getOffset(), info);
                }
            }

            AddressIterator ite = sm.getExternalEntryPointIterator();
            while (ite.hasNext()) {
                Address addr = ite.next();
                Function func = functionManager.getFunctionContaining(addr);
                bin.entry_points.add(addr.getOffset());
                if (func != null)
                    bin.export_functions.put(addr.getOffset(), func.getName());
            }

            for (MemoryBlock b : program.getMemory().getBlocks()) {
                bin.seg.put(b.getStart().getOffset(), b.getName());
            }

            HashMap<Long, Data> dataMap = new HashMap<>();

            // StreamSupport.stream(program.getListing().getExternalFunctions().spliterator(),
            // false).forEach(
            // func -> bin.import_functions.put(func.getEntryPoint().getOffset(),
            // Arrays.asList(func.getName())));
            bin.name = this.binaryFile.getName();
            bin.base = program.getImageBase().getOffset();
            bin.disassembled_at = date_formatter.format(Calendar.getInstance().getTime());
            bin.functions_count = functionManager.getFunctionCount();
            bin.architecture = Platform.CURRENT_PLATFORM.getArchitecture().toString();
            bin.endian = program.getLanguage().isBigEndian() ? "be" : "le";
            bin.bits = "b" + program.getAddressFactory().getDefaultAddressSpace().getSize();
            for (Data dat : program.getListing().getDefinedData(true)) {
                if (dat == null || dat.getValue() == null)
                    continue;
                long offset = dat.getMinAddress().getOffset();
                if (dat.hasStringValue())
                    bin.strings.put(offset, dat.getValue().toString());
                else if (dat.isConstant())
                    bin.data.put(offset, dat.getValue().toString());
                dataMap.put(offset, dat);
            }

            // if (type.contains("unicode") || type.contains("string")) {
            bin.compiler = program.getCompiler();
            model.bin = bin;

            for (Function currentFunction : functionManager.getFunctions(true)) {

                Func func = new Func();
                func.addr_start = currentFunction.getEntryPoint().getOffset();
                func.name = currentFunction.getName();
                func.calls = currentFunction.getCalledFunctions(monitor).stream()// .filter(f -> !f.isExternal())
                        .map(f -> f.getEntryPoint().getOffset()).collect(Collectors.toList());
                // func.api = currentFunction.getCallingFunctions(monitor).stream().filter(f ->
                // f.isExternal())
                // .map(f -> f.getName()).collect(Collectors.toList());
                func.addr_end = currentFunction.getBody().getMaxAddress().getOffset();
                model.functions.add(func);

                if (this.decompiled) {
                    FuncSrc funcSrc = new FuncSrc();
                    funcSrc.src = decomp.decompileFunction(currentFunction, 0, monitor).getDecompiledFunction().getC();
                    model.functions_src.add(funcSrc);
                }

                CodeBlockIterator codeBlockIterator = basicBlockModel.getCodeBlocksContaining(currentFunction.getBody(),
                        monitor);
                while (codeBlockIterator.hasNext()) {
                    CodeBlock codeBlock = codeBlockIterator.next();

                    Block block = new Block();
                    block.addr_f = func.addr_start;
                    block.addr_start = codeBlock.getFirstStartAddress().getOffset();
                    block.name = codeBlock.getName();
                    model.blocks.add(block);
                    func.bbs_len += 1;

                    CodeBlockReferenceIterator codeBlockReferenceDestsIterator = codeBlock.getDestinations(monitor);
                    while (codeBlockReferenceDestsIterator.hasNext()) {
                        CodeBlockReference codeBlockReference = codeBlockReferenceDestsIterator.next();
                        CodeBlock codeBlockDest = codeBlockReference.getDestinationBlock();
                        block.calls.add(codeBlockDest.getFirstStartAddress().getOffset());
                    }

                    Listing listing = program.getListing();
                    CodeUnitIterator codeUnitIterator = listing.getCodeUnits(codeBlock, true);
                    while (codeUnitIterator.hasNext()) {
                        CodeUnit cu = codeUnitIterator.next();
                        if (cu instanceof Instruction) {
                            Instruction instr = (Instruction) cu;
                            Ins ins = new Ins();
                            ins.ea = instr.getAddress().getOffset();
                            ins.mne = instr.getMnemonicString();
                            for (int i = 0; i < instr.getNumOperands(); ++i) {
                                ins.oprs.add(format.getOperandRepresentationString(cu, i));
                                ins.oprs_tp.add(instr.getPrototype().getOpType(i, instr.getInstructionContext()));
                            }

                            for (Reference rf : instr.getReferencesFrom()) {
                                Long offset = rf.getToAddress().getOffset();
                                if (dataMap.containsKey(offset))
                                    ins.dr.add(offset);

                                Function calledFunction = functionManager.getFunctionContaining(rf.getToAddress());
                                if (calledFunction != null) {
                                    ins.cr.add(rf.getToAddress().getOffset());
                                }
                            }

                            block.ins.add(ins);
                        }
                    }
                }
            }

            // model.comments = ParseComments();

            FileOutputStream fStream = null;
            GZIPOutputStream zStream = null;
            try {
                fStream = new FileOutputStream(file);
                zStream = new GZIPOutputStream(new BufferedOutputStream(fStream));
                ObjectMapper mapper = new ObjectMapper();
                mapper.writeValue(zStream, model);
            } finally {
                if (zStream != null) {
                    zStream.flush();
                    zStream.close();
                }
                if (fStream != null) {
                    fStream.flush();
                    fStream.close();
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public List<Comment> ParseComments() throws CancelledException {
        List<Comment> comments = new ArrayList<>();
        ArrayList<Pair<String, Integer>> comment_category_map = new ArrayList<>();
        comment_category_map.add(new Pair<>("anterior", CodeUnit.PRE_COMMENT));
        comment_category_map.add(new Pair<>("posterior", CodeUnit.POST_COMMENT));
        comment_category_map.add(new Pair<>("regular", CodeUnit.PLATE_COMMENT));
        comment_category_map.add(new Pair<>("repeatable", CodeUnit.REPEATABLE_COMMENT));

        Listing listing = program.getListing();
        for (Pair<String, Integer> p : comment_category_map) {
            int comment_category = p.second;
            String comment_type = p.first;

            AddressIterator forward_comment_itr = listing.getCommentAddressIterator(comment_category,
                    program.getMemory(), true);

            while (forward_comment_itr.hasNext()) {
                Address address = forward_comment_itr.next();
                String content = listing.getComment(comment_category, address);

                // Can return null comments for some reason? Weird.
                if (content == null)
                    continue;

                Comment comment = new Comment();
                comment.category = comment_type;
                comment.content = content;
                // This assumes simple block model so no overlap is possible
                comment.address = address.getOffset();
                comment.author = "Ghidra";
                comment.created_at = date_formatter.format(Calendar.getInstance().getTime());

                Function function = program.getFunctionManager().getFunctionContaining(address);
                if (function != null) {
                    comment.address = address.getOffset();
                    comments.add(comment);
                }
            }
        }

        return comments;

    }

}
