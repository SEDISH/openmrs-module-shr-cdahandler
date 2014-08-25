package org.openmrs.module.shr.cdahandler.processor.entry.impl.ihe.pcc;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang.NotImplementedException;
import org.marc.everest.datatypes.ANY;
import org.marc.everest.datatypes.II;
import org.marc.everest.datatypes.ST;
import org.marc.everest.datatypes.TS;
import org.marc.everest.datatypes.doc.StructDocNode;
import org.marc.everest.datatypes.generic.CS;
import org.marc.everest.datatypes.generic.EIVL;
import org.marc.everest.datatypes.generic.IVL;
import org.marc.everest.datatypes.generic.PIVL;
import org.marc.everest.datatypes.interfaces.ISetComponent;
import org.marc.everest.formatters.FormatterUtil;
import org.marc.everest.interfaces.IGraphable;
import org.marc.everest.rmim.uv.cdar2.pocd_mt000040uv.Act;
import org.marc.everest.rmim.uv.cdar2.pocd_mt000040uv.ClinicalStatement;
import org.marc.everest.rmim.uv.cdar2.pocd_mt000040uv.EntryRelationship;
import org.marc.everest.rmim.uv.cdar2.pocd_mt000040uv.ManufacturedProduct;
import org.marc.everest.rmim.uv.cdar2.pocd_mt000040uv.Material;
import org.marc.everest.rmim.uv.cdar2.pocd_mt000040uv.Precondition;
import org.marc.everest.rmim.uv.cdar2.pocd_mt000040uv.Reference;
import org.marc.everest.rmim.uv.cdar2.pocd_mt000040uv.Section;
import org.marc.everest.rmim.uv.cdar2.pocd_mt000040uv.SubstanceAdministration;
import org.marc.everest.rmim.uv.cdar2.pocd_mt000040uv.Supply;
import org.marc.everest.rmim.uv.cdar2.vocabulary.ActPriority;
import org.marc.everest.rmim.uv.cdar2.vocabulary.ActStatus;
import org.marc.everest.rmim.uv.cdar2.vocabulary.ParticipationAuthorOriginator;
import org.marc.everest.rmim.uv.cdar2.vocabulary.x_ActRelationshipEntryRelationship;
import org.marc.everest.rmim.uv.cdar2.vocabulary.x_ActRelationshipExternalReference;
import org.marc.everest.rmim.uv.cdar2.vocabulary.x_DocumentSubstanceMood;
import org.openmrs.BaseOpenmrsData;
import org.openmrs.Concept;
import org.openmrs.Drug;
import org.openmrs.DrugOrder;
import org.openmrs.Encounter;
import org.openmrs.Obs;
import org.openmrs.Order;
import org.openmrs.Order.Action;
import org.openmrs.Order.Urgency;
import org.openmrs.api.OrderContext;
import org.openmrs.api.context.Context;
import org.openmrs.module.shr.cdahandler.CdaHandlerConstants;
import org.openmrs.module.shr.cdahandler.exception.DocumentImportException;
import org.openmrs.module.shr.cdahandler.exception.DocumentPersistenceException;
import org.openmrs.module.shr.cdahandler.exception.DocumentValidationException;
import org.openmrs.module.shr.cdahandler.exception.ValidationIssueCollection;
import org.openmrs.module.shr.cdahandler.order.ProcedureOrder;
import org.openmrs.module.shr.cdahandler.processor.annotation.ProcessTemplates;
import org.openmrs.module.shr.cdahandler.processor.context.ProcessorContext;
import org.openmrs.module.shr.cdahandler.processor.entry.impl.SubstanceAdministrationEntryProcessor;

/**
 * Medications Entry Processor
 */
@ProcessTemplates(templateIds = {
		CdaHandlerConstants.ENT_TEMPLATE_MEDICATIONS,
		CdaHandlerConstants.ENT_TEMPLATE_MEDICATIONS_SPLIT_DOSING,
		CdaHandlerConstants.ENT_TEMPLATE_MEDICATIONS_CONDITIONAL_DOSING,
		CdaHandlerConstants.ENT_TEMPLATE_MEDICATIONS_COMBINATION_DOSING
})
public class MedicationsEntryProcessor extends SubstanceAdministrationEntryProcessor {

	
	/**
	 * Get the expected entry relationships
	 */
	@Override
	protected List<String> getExpectedEntryRelationships() {
		return null;
	}
	
	/**
	 * Get the template name
	 */
	@Override
	public String getTemplateName() {
		return "Medications";
	}

	
	
	/**
	 * Process this as an order
	 */
	@Override
	protected BaseOpenmrsData processAdministrationAsOrder(SubstanceAdministration administration) throws DocumentImportException {
		Encounter encounterInfo = (Encounter)this.getEncounterContext().getParsedObject();

		// Get current order and void if existing for an update
		Order previousOrder = super.voidOrThrowIfPreviousOrderExists(administration.getReference(), encounterInfo.getPatient(), administration.getId());

		// Now we create a new order
		DrugOrder res = new DrugOrder();

		res.setPreviousOrder(previousOrder);
		
		// OpenMRS Hack:
		// Technically an encounter must exist before the order can be created, however the encounter from the document
		// (describing and grouping the document) might have occurred (most likely) after this order record as the CDA 
		// is saying did happen. So what we do is create an encounter for the administration of the drug
		res.setPatient(encounterInfo.getPatient());
		res.setDateCreated(encounterInfo.getDateCreated());
		res.setEncounter(encounterInfo);
		
		// Set the creator
		super.setCreator(res, administration);
		
		// Is this a prescribe? 
		if(previousOrder != null)
			res.setAction(Action.REVISE);
		else
			res.setAction(Action.NEW);
			
		// Set the ID
		if(administration.getId() != null && !administration.getId().isNull())
			res.setAccessionNumber(this.m_datatypeUtil.formatIdentifier(administration.getId().get(0)));
		
		// The type of procedure or administration done
		if(administration.getCode() != null && !administration.getCode().isNull())
			res.setConcept(this.m_conceptUtil.getOrCreateConceptAndEquivalents(administration.getCode()));
		
		// Effective time(s)
		Date discontinueDate = null;
		for(ISetComponent<TS> eft : administration.getEffectiveTime())
		{
			// TODO: Is there a better way to represent these frequencies in oMRS than as serializations of the data types? Like a coded list?
			if(eft instanceof IVL) // The effective range time
			{
				
				IVL<TS> effectiveRange = ((IVL<TS>)eft).toBoundIvl();
				if(effectiveRange.getLow() != null && !effectiveRange.getLow().isNull())
				{
					// In the future
					if(administration.getMoodCode().getCode().equals(x_DocumentSubstanceMood.Intent)) // Scheduled to occur
						res.setScheduledDate(effectiveRange.getLow().getDateValue().getTime());
					else // Did occur
					{
						res.setDateActivated(effectiveRange.getLow().getDateValue().getTime());
						encounterInfo.setEncounterDatetime(res.getDateActivated());
					}
				}
				if(effectiveRange.getHigh() != null && !effectiveRange.getHigh().isNull())
				{
					if(administration.getMoodCode().getCode().equals(x_DocumentSubstanceMood.Eventoccurrence))
						discontinueDate = effectiveRange.getHigh().getDateValue().getTime();
					else
						res.setAutoExpireDate(effectiveRange.getHigh().getDateValue().getTime());
				}
			}
			else if(eft instanceof PIVL || eft instanceof EIVL || eft instanceof TS) // period
			{
				res.setFrequency(this.m_datatypeUtil.getOrCreateOrderFrequency((ANY)eft));
			}
			else
				throw new DocumentPersistenceException(String.format("Cannot represent administration frequency of %s", FormatterUtil.createXsiTypeName(eft)));
		}
				
		// Dosage? 
		if(administration.getDoseQuantity().getValue() != null)
		{
			res.setDose(administration.getDoseQuantity().getValue().getValue().doubleValue());
			if(administration.getDoseQuantity().getValue().getUnit() != null)
				res.setDoseUnits(this.m_conceptUtil.getOrCreateUcumConcept(administration.getDoseQuantity().getValue().getUnit()));
		}
		else // TODO: Make these Obs maybe?
			throw new DocumentPersistenceException("OpenSHR only supports explicit (non-ranged) doses for administrations");
				
		// Now route
		if(administration.getRouteCode() != null && !administration.getRouteCode().isNull())
			res.setRoute(this.m_conceptUtil.getOrCreateRouteConcept(administration.getRouteCode()));

		// Drug time ... 
		if(administration.getConsumable() != null && administration.getConsumable().getNullFlavor() == null &&
				administration.getConsumable().getManufacturedProduct() != null && administration.getConsumable().getManufacturedProduct().getNullFlavor() == null)
		{
			ManufacturedProduct product = administration.getConsumable().getManufacturedProduct();
			if(product.getManufacturedDrugOrOtherMaterialIfManufacturedLabeledDrug() != null) // A labelled drug
				;
			else
			{
				Material drugMaterial =  product.getManufacturedDrugOrOtherMaterialIfManufacturedMaterial();
				
				if(drugMaterial.getCode() != null)
					res.setDrug(this.m_conceptUtil.getOrCreateDrugFromConcept(drugMaterial.getCode(), drugMaterial.getName(), administration.getAdministrationUnitCode()));
				else
					throw new DocumentImportException("Drug must carry a coded identifier for the product administered");
			}
		}
		
		// reason will link to another obs
		StringBuilder reason = new StringBuilder(); 
		for(EntryRelationship er : this.findEntryRelationship(administration, CdaHandlerConstants.ENT_TEMPLATE_INTERNAL_REFERENCE))
			if(er.getTypeCode().getCode().equals(x_ActRelationshipEntryRelationship.HasReason))
				reason.append(String.format("%s;",this.m_datatypeUtil.formatIdentifier(er.getClinicalStatementIfAct().getId().get(0))));
		if(!reason.toString().isEmpty())
			res.setOrderReasonNonCoded(reason.toString());

		// Get the section text
		Section parentSection = super.getSection();
		// Instructions
		for(EntryRelationship er : this.findEntryRelationship(administration, CdaHandlerConstants.ENT_TEMPLATE_MEDICATION_INSTRUCTIONS))
		{
			// Get the text
			Act instructionAct = er.getClinicalStatementIfAct();
			if(instructionAct == null || instructionAct.getText() == null) continue;
			// Get instruction
			res.setInstructions(parentSection.getText().findNodeById(instructionAct.getText().getReference().getValue()).toString());
			break;
		}
		
		// Conditions, or dosing instructions
		StringBuilder dosingInstructions = new StringBuilder();
		for(Precondition condition : administration.getPrecondition())
		{
			if(condition.getNullFlavor() != null || condition.getCriterion() == null ||
					condition.getCriterion().getNullFlavor() != null ||
					condition.getCriterion().getText() == null ||
					condition.getCriterion().getText().getReference() == null)
				continue;
			
			// Add instructions
			StructDocNode instructionsNode = this.getSection().getText().findNodeById(condition.getCriterion().getText().getReference().getValue());
			if(instructionsNode != null)
				dosingInstructions.append(String.format("%s, ", instructionsNode.toPlainString()));
		}
		res.setDosingInstructions(dosingInstructions.toString());
		
		// This is the person who performed the dispense or is intended to perform the dispense
		OrderContext orderContext = new OrderContext();

		// Prescription (supply) entry relationship?
		for(EntryRelationship er : this.findEntryRelationship(administration, CdaHandlerConstants.ENT_TEMPLATE_SUPPLY))
			if(er.getTypeCode().getCode().equals(x_ActRelationshipEntryRelationship.REFR))
			{
				
				Supply supply = er.getClinicalStatementIfSupply();
				
				// Repeat number
				if(supply.getRepeatNumber() != null && !supply.getRepeatNumber().isNull() &&
						supply.getRepeatNumber().getValue() != null)
					res.setNumRefills(supply.getRepeatNumber().getValue().toInteger());

				// Quantity
				if(supply.getQuantity() != null && !supply.getQuantity().isNull())
				{
					res.setQuantity(supply.getQuantity().getValue().doubleValue());
					res.setQuantityUnits(this.m_conceptUtil.getOrCreateUcumConcept(supply.getQuantity().getUnit()));
				}
					
				// Author, who prescribed?
				if(supply.getAuthor().size() > 0)
					res.setOrderer(this.m_providerUtil.processProvider(supply.getAuthor().get(0).getAssignedAuthor()));
				
				// Performer, who performed the fulfillment?
				if(supply.getPerformer().size() > 0)
				{
					orderContext.setAttribute("PRF", this.m_providerUtil.processProvider(supply.getPerformer().get(0).getAssignedEntity()));
				}
			}

		// Get orderer 
		if(res.getOrderer() == null &&
				administration.getAuthor().size() == 1)
			res.setOrderer(this.m_providerUtil.processProvider(administration.getAuthor().get(0).getAssignedAuthor()));
		else if(res.getOrderer() == null)
			res.setOrderer(encounterInfo.getProvidersByRole(this.m_metadataUtil.getOrCreateEncounterRole(new CS<ParticipationAuthorOriginator>(ParticipationAuthorOriginator.Authororiginator))).iterator().next());

		// Priority
		if(administration.getPriorityCode() != null)
			this.m_dataUtil.setOrderPriority(res, administration.getPriorityCode().getCode());

		res.setCareSetting(this.m_metadataUtil.getOrCreateInpatientCareSetting());
		
		// Save the order 
		res = (DrugOrder)Context.getOrderService().saveOrder(res, orderContext);

		// Is this an event? If so it happened in the past and isn't active so we have to discontinue it
		if(discontinueDate != null)
			try
			{
				res = (DrugOrder)Context.getOrderService().discontinueOrder(res, administration.getStatusCode().getCode().getCode(), discontinueDate, null, encounterInfo);
			}
			catch(Exception e)
			{
				throw new DocumentPersistenceException(e);
			}

		return res;
	}

	/**
	 * Process administration as an observation
	 * Auto generated method comment
	 * 
	 * @param adm
	 * @return
	 * @throws DocumentImportException 
	 */
	@Override
	protected BaseOpenmrsData processAdministrationAsObservation(SubstanceAdministration administration) throws DocumentImportException
	{
		Encounter encounterInfo = (Encounter)this.getEncounterContext().getParsedObject();
		Obs parentObs = (Obs)this.getContext().getParsedObject();
		
		// Get current order and void if existing for an update
		Obs previousHistoryObs = super.voidOrThrowIfPreviousObsExists(administration.getReference(), encounterInfo.getPatient(), administration.getId());

		// Obs for the medication history entry
		Obs medicationHistoryObs = new Obs();
		medicationHistoryObs.setObsGroup(parentObs);
		medicationHistoryObs.setPreviousVersion(previousHistoryObs);
		medicationHistoryObs.setConcept(Context.getConceptService().getConcept(160741));
		medicationHistoryObs.setPerson(encounterInfo.getPatient());
		medicationHistoryObs.setLocation(encounterInfo.getLocation());
		medicationHistoryObs.setObsDatetime(encounterInfo.getEncounterDatetime());
		medicationHistoryObs.setEncounter(encounterInfo);
		medicationHistoryObs.setDateCreated(encounterInfo.getDateCreated());
		super.setCreator(medicationHistoryObs, administration);				
		
		// Prescription (supply) entry relationship?
		for(EntryRelationship er : this.findEntryRelationship(administration, CdaHandlerConstants.ENT_TEMPLATE_SUPPLY))
			if(er.getTypeCode().getCode().equals(x_ActRelationshipEntryRelationship.REFR))
			{
				
				Order ord = (Order)this.processAdministrationAsOrder(administration);
				medicationHistoryObs.setOrder(ord);
			}
		
		// Effective time(s)
		for(ISetComponent<TS> eft : administration.getEffectiveTime())
		{

			// TODO: Is there a better way to represent these frequencies in oMRS than as serializations of the data types? Like a coded list?
			if(eft instanceof IVL) // The effective range time
			{
				
				IVL<TS> effectiveRange = ((IVL<TS>)eft).toBoundIvl();
				if(effectiveRange.getLow() != null && !effectiveRange.getLow().isNull())
					this.m_dataUtil.addSubObservationValue(medicationHistoryObs, Context.getConceptService().getConcept(1190), effectiveRange.getLow());
				if(effectiveRange.getHigh() != null && !effectiveRange.getHigh().isNull())
					this.m_dataUtil.addSubObservationValue(medicationHistoryObs, Context.getConceptService().getConcept(1191), effectiveRange.getHigh());
			}
			else if(eft instanceof PIVL || eft instanceof EIVL || eft instanceof TS) // period
			{
				Concept frequency = this.m_conceptUtil.getOrCreateFrequencyConcept((ANY)eft);
				this.m_dataUtil.addSubObservationValue(medicationHistoryObs, Context.getConceptService().getConcept(160855), frequency);
			}
			else
				throw new DocumentPersistenceException(String.format("Cannot represent administration frequency of %s", FormatterUtil.createXsiTypeName(eft)));
		}
		
		// Dosage? 
		if(administration.getDoseQuantity() != null)
			this.m_dataUtil.addSubObservationValue(medicationHistoryObs, Context.getConceptService().getConcept(1444), administration.getDoseQuantity().toString());
			
		// Now form
		if(administration.getAdministrationUnitCode() != null && !administration.getAdministrationUnitCode().isNull())
		{
			Concept formCode = this.m_conceptUtil.getOrCreateDrugAdministrationFormConcept(administration.getAdministrationUnitCode());
			this.m_dataUtil.addSubObservationValue(medicationHistoryObs, Context.getConceptService().getConcept(1519), formCode);
		}
		
		// Now route
		if(administration.getRouteCode() != null && !administration.getRouteCode().isNull())
		{
			Concept routeCodeConcept = this.m_conceptUtil.getOrCreateDrugRouteConcept();
			this.m_dataUtil.addSubObservationValue(medicationHistoryObs, routeCodeConcept, administration.getRouteCode());
		}

		// Get the section text
		Section parentSection = super.getSection();

		// Instructions
		for(EntryRelationship er : this.findEntryRelationship(administration, CdaHandlerConstants.ENT_TEMPLATE_MEDICATION_INSTRUCTIONS))
		{
			// Get the text
			Act instructionAct = er.getClinicalStatementIfAct();
			if(instructionAct == null || instructionAct.getText() == null) continue;
			// Get instruction
			medicationHistoryObs.setComment(String.format("Instructions: %s", parentSection.getText().findNodeById(instructionAct.getText().getReference().getValue()).toPlainString()));
			break;
		}		

		// Drug time ... 
		if(administration.getConsumable() != null && administration.getConsumable().getNullFlavor() == null &&
				administration.getConsumable().getManufacturedProduct() != null && administration.getConsumable().getManufacturedProduct().getNullFlavor() == null)
		{
			ManufacturedProduct product = administration.getConsumable().getManufacturedProduct();
			if(product.getManufacturedDrugOrOtherMaterialIfManufacturedLabeledDrug() != null) // A labelled drug
				;
			else
			{
				Material drugMaterial =  product.getManufacturedDrugOrOtherMaterialIfManufacturedMaterial();
				
				if(drugMaterial.getCode() != null)
				{
					
					Drug drug = this.m_conceptUtil.getOrCreateDrugFromConcept(drugMaterial.getCode(), drugMaterial.getName(), administration.getAdministrationUnitCode());
					this.m_dataUtil.addSubObservationValue(medicationHistoryObs, Context.getConceptService().getConcept(1282), drug.getConcept());
				}
				else
					throw new DocumentImportException("Drug must carry a coded identifier for the product administered");
			}
		}
		
		// Prescription (supply) entry relationship?
		for(EntryRelationship er : this.findEntryRelationship(administration, CdaHandlerConstants.ENT_TEMPLATE_SUPPLY))
			if(er.getTypeCode().getCode().equals(x_ActRelationshipEntryRelationship.REFR))
			{
				Order ord = (Order)this.processAdministrationAsOrder(administration);
				medicationHistoryObs.setOrder(ord);
			}
		
		// reasoning will link to another obs
		// TODO: Map this to 160742 concept by walking down the CDA tree
		for(EntryRelationship er : this.findEntryRelationship(administration, CdaHandlerConstants.ENT_TEMPLATE_INTERNAL_REFERENCE))
			if(er.getTypeCode().getCode().equals(x_ActRelationshipEntryRelationship.HasReason))
			{
				ST reasonText = new ST(this.m_datatypeUtil.formatIdentifier(er.getClinicalStatementIfAct().getId().get(0)));
				this.m_dataUtil.addSubObservationValue(medicationHistoryObs, this.m_conceptUtil.getOrCreateRMIMConcept(CdaHandlerConstants.RMIM_CONCEPT_NAME_REASON, reasonText), reasonText);
			}

		// Conditions, or dosing instructions
		for(Precondition condition : administration.getPrecondition())
		{
			if(condition.getNullFlavor() != null || condition.getCriterion() == null ||
					condition.getCriterion().getNullFlavor() != null ||
					condition.getCriterion().getText() == null ||
					condition.getCriterion().getText().getReference() == null)
				continue;
			
			// Add instructions
			StructDocNode instructionsNode = this.getSection().getText().findNodeById(condition.getCriterion().getText().getReference().getValue());
			if(instructionsNode != null)
				this.m_dataUtil.addSubObservationValue(medicationHistoryObs, Context.getConceptService().getConcept(160632), instructionsNode.toPlainString());
		}

		// Medication history observation
		medicationHistoryObs = Context.getObsService().saveObs(medicationHistoryObs, null);

		// Process entry relationships (these should be substance administrations) 
		// Representing them as a flat heirarchy
		ProcessorContext childContext = new ProcessorContext(administration, medicationHistoryObs, this);
		super.processEntryRelationships(administration, childContext);
		
		return medicationHistoryObs;		
	}
	
	/**
	 * Validate the instance
	 */
	@Override
    public ValidationIssueCollection validate(IGraphable object) {
		ValidationIssueCollection validationIssues = super.validate(object);
		if(validationIssues.hasErrors())
			return validationIssues;
		
		// Now validate the explicit requirements
		SubstanceAdministration sbadm = (SubstanceAdministration)object;
		if(sbadm.getEffectiveTime().size() < 2)
			validationIssues.error("Medications section shall have at minimum two effectiveTime elements representing the date and frequency of administration");
		if(sbadm.getDoseQuantity() == null || sbadm.getDoseQuantity().isNull())
			validationIssues.error("Medications section shall have a dose quantity");
		if(sbadm.getConsumable() == null || sbadm.getConsumable().getNullFlavor() != null)
			validationIssues.error("Medications section must have a consumable");
		else if(sbadm.getConsumable().getManufacturedProduct() == null || sbadm.getConsumable().getManufacturedProduct().getNullFlavor() != null)
			validationIssues.error("Consumable must have a manufacturedProduct node");
		
		// If route of administration is provided must be RouteOfAdministration code set
		if(sbadm.getRouteCode() != null && !sbadm.getRouteCode().isNull() && 
				!CdaHandlerConstants.CODE_SYSTEM_ROUTE_OF_ADMINISTRATION.equals(sbadm.getRouteCode().getCodeSystem()))
			validationIssues.error("If the route is known, the routeCode must be populated using the HL7 RouteOfAdministration valueset");
		return validationIssues;
    }
	
	
}
