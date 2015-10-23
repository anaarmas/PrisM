package uk.ac.ox.cs.pagoda.rules;

import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.semanticweb.HermiT.Configuration;
import org.semanticweb.HermiT.model.AnnotatedEquality;
import org.semanticweb.HermiT.model.Atom;
import org.semanticweb.HermiT.model.AtomicConcept;
import org.semanticweb.HermiT.model.AtomicDataRange;
import org.semanticweb.HermiT.model.AtomicNegationDataRange;
import org.semanticweb.HermiT.model.AtomicRole;
import org.semanticweb.HermiT.model.ConstantEnumeration;
import org.semanticweb.HermiT.model.DLClause;
import org.semanticweb.HermiT.model.DLOntology;
import org.semanticweb.HermiT.model.DLPredicate;
import org.semanticweb.HermiT.model.Equality;
import org.semanticweb.HermiT.model.Inequality;
import org.semanticweb.HermiT.model.InverseRole;
import org.semanticweb.HermiT.model.Term;
import org.semanticweb.HermiT.model.Variable;
import org.semanticweb.HermiT.structural.OWLClausification;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLObjectInverseOf;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import org.semanticweb.owlapi.model.OWLSubAnnotationPropertyOfAxiom;
import org.semanticweb.owlapi.model.OWLSubPropertyChainOfAxiom;
import org.semanticweb.owlapi.model.OWLTransitiveObjectPropertyAxiom;
import org.semanticweb.simpleETL.SimpleETL;

import uk.ac.ox.cs.pagoda.MyPrefixes;
import uk.ac.ox.cs.pagoda.approx.KnowledgeBase;
import uk.ac.ox.cs.pagoda.approx.RLPlusOntology;
import uk.ac.ox.cs.pagoda.constraints.BottomStrategy;
import uk.ac.ox.cs.pagoda.constraints.NullaryBottom;
import uk.ac.ox.cs.pagoda.constraints.PredicateDependency;
import uk.ac.ox.cs.pagoda.hermit.DLClauseHelper;
import uk.ac.ox.cs.pagoda.owl.OWLHelper;
import uk.ac.ox.cs.pagoda.util.Utility;

@Deprecated
public abstract class Program implements KnowledgeBase {
	
	protected String ontologyDirectory = null;
	protected OWLOntology ontology; 
	protected DLOntology dlOntology;
	protected BottomStrategy botStrategy; 

	protected String additionalDataFile = null; 
	
	protected Collection<DLClause> clauses = new LinkedList<DLClause>();
//	protected PredicateDependency dependencyGraph; 
	
	/**
	 * clone all information of another program after load()
	 * 
	 * @param program
	 */
	void clone(Program program) {
		this.ontologyDirectory = program.ontologyDirectory; 
		this.ontology = program.ontology; 
		this.dlOntology = program.dlOntology;
		this.botStrategy = program.botStrategy; 
		this.additionalDataFile = program.additionalDataFile; 
		this.transitiveAxioms = program.transitiveAxioms;  
		this.transitiveClauses = program.transitiveClauses; 
		this.subPropChainAxioms = program.subPropChainAxioms; 
		this.subPropChainClauses = program.subPropChainClauses; 
	}
	
	public void load(OWLOntology o, BottomStrategy botStrategy) {
		this.botStrategy = botStrategy; 
		RLPlusOntology owlOntology = new RLPlusOntology(); 
		owlOntology.load(o, new NullaryBottom());
		owlOntology.simplify();

		ontology = owlOntology.getTBox(); 
		String ontologyPath = OWLHelper.getOntologyPath(ontology); 
		ontologyDirectory = ontologyPath.substring(0, ontologyPath.lastIndexOf(Utility.FILE_SEPARATOR));
		clausify(); 
		
		String aboxOWLFile = owlOntology.getABoxPath();
		OWLOntology abox = OWLHelper.loadOntology(aboxOWLFile);
		OWLOntologyManager manager = abox.getOWLOntologyManager(); 
		OWLAxiom axiom; 
		for (Atom atom: dlOntology.getPositiveFacts()) {
			if ((axiom = OWLHelper.getABoxAssertion(manager.getOWLDataFactory(), atom)) != null)
				manager.addAxiom(abox, axiom); 
		}
		
		try {
			FileOutputStream out = new FileOutputStream(aboxOWLFile); 
			manager.saveOntology(abox, out);
			out.close();
		} catch (OWLOntologyStorageException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		if (!abox.isEmpty()) {
			SimpleETL rewriter = new SimpleETL(owlOntology.getOntologyIRI(), aboxOWLFile);
			try {
				rewriter.rewrite();
			} catch (Exception e) {
				e.printStackTrace();
			} 
			additionalDataFile = rewriter.getExportedFile(); 
		}
		
	}
	
	protected void clausify() {
		Configuration conf = new Configuration();
		OWLClausification clausifier = new OWLClausification(conf);
		OWLOntology filteredOntology = null;
		OWLOntologyManager manager = ontology.getOWLOntologyManager();
		try {
			filteredOntology = manager.createOntology();
		} catch (OWLOntologyCreationException e) {
			e.printStackTrace();
		}
		
		transitiveAxioms = new LinkedList<OWLTransitiveObjectPropertyAxiom>();
		subPropChainAxioms = new LinkedList<OWLSubPropertyChainOfAxiom>();
		
		int noOfDataPropertyRangeAxioms = 0, noOfAxioms = 0; 
		for (OWLOntology onto: ontology.getImportsClosure())
			for (OWLAxiom axiom: onto.getAxioms()) {
				if (axiom instanceof OWLTransitiveObjectPropertyAxiom) 
					transitiveAxioms.add((OWLTransitiveObjectPropertyAxiom) axiom);
				else if (axiom instanceof OWLSubPropertyChainOfAxiom) 
					subPropChainAxioms.add((OWLSubPropertyChainOfAxiom) axiom);
				// TODO to filter out datatype axioms
				else if (axiom instanceof OWLDataPropertyRangeAxiom) {
					++noOfDataPropertyRangeAxioms; 
				}
				else {
					manager.addAxiom(filteredOntology, axiom);
				}
				
				if (axiom instanceof OWLAnnotationAssertionAxiom ||
						axiom instanceof OWLSubAnnotationPropertyOfAxiom ||
						axiom instanceof OWLDeclarationAxiom ||
						axiom instanceof OWLDataPropertyRangeAxiom); 
				else {
//					System.out.println(axiom); 
					++noOfAxioms;
				}
					
			}
		Utility.logInfo("The number of data property range axioms that are ignored: " + noOfDataPropertyRangeAxioms + "(" + noOfAxioms + ")");
		
		dlOntology = (DLOntology)clausifier.preprocessAndClausify(filteredOntology, null)[1];
		clausifier = null;
	}
	
	public String getAdditionalDataFile() {
		return additionalDataFile; 
	}

	protected LinkedList<OWLTransitiveObjectPropertyAxiom> transitiveAxioms;
	protected LinkedList<DLClause> transitiveClauses;
	
	protected LinkedList<OWLSubPropertyChainOfAxiom> subPropChainAxioms; 
	protected LinkedList<DLClause> subPropChainClauses;
	
	public void transform() {
		for (DLClause dlClause: dlOntology.getDLClauses()) {
			DLClause simplifiedDLClause = DLClauseHelper.removeNominalConcept(dlClause);
			simplifiedDLClause = removeAuxiliaryBodyAtoms(simplifiedDLClause);
			simplifiedDLClause  = DLClauseHelper.replaceWithDataValue(simplifiedDLClause);
			convert(simplifiedDLClause);
		}

		addingTransitiveAxioms();
		addingSubPropertyChainAxioms();
		
		Collection<DLClause> botRelated = new LinkedList<DLClause>(); 
		Variable X = Variable.create("X"); 
		botRelated.add(DLClause.create(new Atom[0], new Atom[] {Atom.create(Inequality.INSTANCE, X, X)}));
		clauses.addAll(botStrategy.process(botRelated)); 
		
	}
	
	protected DLClause removeAuxiliaryBodyAtoms(DLClause dlClause) {
		Collection<Atom> newBodyAtoms = new LinkedList<Atom>();
		DLPredicate p; 
		for (Atom bodyAtom: dlClause.getBodyAtoms()) {
			p = bodyAtom.getDLPredicate(); 
			if (p instanceof AtomicConcept || 
					p instanceof AtomicRole || p instanceof InverseRole ||
					p instanceof Equality || p instanceof AnnotatedEquality || p instanceof Inequality)
				newBodyAtoms.add(bodyAtom); 
		}
		LinkedList<Atom> newHeadAtoms = new LinkedList<Atom>();
		Map<Variable, Term> assign = new HashMap<Variable, Term>(); 
		for (Atom headAtom: dlClause.getHeadAtoms()) {
			p = headAtom.getDLPredicate(); 
			if (p instanceof AtomicNegationDataRange) {
				AtomicDataRange positive = ((AtomicNegationDataRange) p).getNegatedDataRange(); 
				if (!(positive instanceof ConstantEnumeration)) 
					newBodyAtoms.add(Atom.create(positive, headAtom.getArgument(0)));
				else if (((ConstantEnumeration) positive).getNumberOfConstants() == 1) {
					assign.put((Variable) headAtom.getArgument(0), ((ConstantEnumeration) positive).getConstant(0)); 
//					newBodyAtoms.add(Atom.create(Equality.INSTANCE, headAtom.getArgument(0), ((ConstantEnumeration) positive).getConstant(0))); 
				}
				else newHeadAtoms.add(headAtom); 
			}
			else 
				newHeadAtoms.add(headAtom); 
		}
		
		if (assign.isEmpty() && newHeadAtoms.isEmpty() && newBodyAtoms.size() == dlClause.getBodyLength())
			return dlClause; 

		Atom[] headArray = newHeadAtoms.size() == dlClause.getHeadLength() ? dlClause.getHeadAtoms() : newHeadAtoms.toArray(new Atom[0]);
		Atom[] bodyArray = newBodyAtoms.size() == dlClause.getBodyLength() ? dlClause.getBodyAtoms() : newBodyAtoms.toArray(new Atom[0]);
		if (!assign.isEmpty()) {
			for (int i = 0; i < headArray.length; ++i)
				headArray[i] = DLClauseHelper.getInstance(headArray[i], assign); 
			for (int i = 0; i < bodyArray.length; ++i)
				bodyArray[i] = DLClauseHelper.getInstance(bodyArray[i], assign); 
		}
		return DLClause.create(headArray, bodyArray); 
	}

	protected void addingTransitiveAxioms() {
		DLClause transitiveClause;
		Atom headAtom;
		Variable X = Variable.create("X"), Y = Variable.create("Y"), Z = Variable.create("Z");
		transitiveClauses = new LinkedList<DLClause>();
		for (OWLTransitiveObjectPropertyAxiom axiom: transitiveAxioms) {
			OWLObjectPropertyExpression objExp = axiom.getProperty(); 
			headAtom = getAtom(objExp, X, Z);
			Atom[] bodyAtoms = new Atom[2];
			bodyAtoms[0] = getAtom(objExp, X, Y); 
			bodyAtoms[1] = getAtom(objExp, Y, Z); 
			transitiveClause = DLClause.create(new Atom[] {headAtom}, bodyAtoms); 
			clauses.add(transitiveClause);
			transitiveClauses.add(transitiveClause);
		}
	}
	
	protected Atom getAtom(OWLObjectPropertyExpression exp, Variable x, Variable y) {
		if (exp instanceof OWLObjectProperty)
			return Atom.create(AtomicRole.create(((OWLObjectProperty) exp).toStringID()), x, y);
		OWLObjectInverseOf inverseOf; 
		if (exp instanceof OWLObjectInverseOf && (inverseOf = (OWLObjectInverseOf) exp).getInverse() instanceof OWLObjectProperty)
			return Atom.create(AtomicRole.create(((OWLObjectProperty) inverseOf).toStringID()), x, y);
		return null;
	}

	protected void addingSubPropertyChainAxioms() {
		DLClause dlClause; 
		subPropChainClauses = new LinkedList<DLClause>();
		Atom headAtom;
		Iterator<OWLObjectPropertyExpression> iterExp; 
		OWLObjectPropertyExpression objExp; 
		for (OWLSubPropertyChainOfAxiom axiom: subPropChainAxioms) {
			objExp = axiom.getSuperProperty();
			List<OWLObjectPropertyExpression> objs = axiom.getPropertyChain();
			headAtom = getAtom(objExp, Variable.create("X"), Variable.create("X" + objs.size()));
			iterExp = objs.iterator();
			int index = 1; 
			Atom[] bodyAtoms = new Atom[objs.size()]; 
			bodyAtoms[0] = getAtom(iterExp.next(), Variable.create("X"), Variable.create("X1")); 
			while (index < objs.size()) {
				bodyAtoms[index] = getAtom(iterExp.next(), Variable.create("X" + index), Variable.create("X" + (index + 1)));
				++index; 
			}
			dlClause = DLClause.create(new Atom[] {headAtom}, bodyAtoms); 
			clauses.add(dlClause); 
			subPropChainClauses.add(dlClause); 
		}
	}

	public void save() {
		try {
			BufferedWriter ruleWriter = new BufferedWriter(new OutputStreamWriter(
					new FileOutputStream(getOutputPath())));
			ruleWriter.write(toString());
			ruleWriter.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		Utility.logDebug("The rules are saved in " + getOutputPath() + "."); 
	}
	
	@Override
	public String toString() {
		return toString(clauses); 
	}
	
	public static String toString(Collection<DLClause> clauses) {
		StringBuilder sb = new StringBuilder(DLClauseHelper.toString(clauses)); 
		sb.insert(0, MyPrefixes.PAGOdAPrefixes.prefixesText()); 
		return sb.toString(); 
	}
	
	public final void convert(DLClause clause) {
		Collection<DLClause> tempClauses = convert2Clauses(clause);
		clauses.addAll(tempClauses);
	}
	
	public abstract Collection<DLClause> convert2Clauses(DLClause clause);

	public abstract String getOutputPath();
	
	
	public OWLOntology getOntology() {
		return ontology;
	}
	
	public Collection<DLClause> getClauses() {
		return clauses;
	}
	
	public final String getDirectory() {
		return Utility.TempDirectory; 
	}
}
