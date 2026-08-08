package com.smartfirehub.ontology.element;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.ontology.element.dto.ElementDtos.CreateEntityTypeRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.EntityTypeDeletion;
import com.smartfirehub.ontology.element.dto.ElementDtos.EntityTypeMutation;
import com.smartfirehub.ontology.element.dto.ElementDtos.CreatePropertyRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.CreateRelationRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.PatchOntologyRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.PropertyMutation;
import com.smartfirehub.ontology.element.dto.ElementDtos.RelationMutation;
import com.smartfirehub.ontology.element.dto.ElementDtos.UpdateEntityTypeRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.UpdatePropertyRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.UpdateRelationRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.VersionOnly;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 지식 모델 요소 단위 편집 API. 전체 스키마를 왕복시키는 PUT /ontology/{id}와 공존하며,
// 웹 에디터(S2)가 이쪽으로 옮겨온 뒤 PUT은 제거된다.
// 경로 접두사를 /ontology/{id}로 두어 "어느 온톨로지의 요소인가"가 URL에 드러나게 한다 —
// 요소 id만으로는 남의 온톨로지를 건드리는 것을 서버가 막을 근거가 URL에 없다.
@RestController
@RequestMapping("/api/v1/ontology/{ontologyId}")
@RequiredArgsConstructor
public class OntologyElementController {

  private final OntologyElementService elementService;

  @PatchMapping
  @RequirePermission("ontology:write")
  public VersionOnly patchOntology(@PathVariable long ontologyId, @RequestBody PatchOntologyRequest request) {
    return elementService.patchDomain(ontologyId, request);
  }

  @PostMapping("/entity-types")
  @RequirePermission("ontology:write")
  public EntityTypeMutation addEntityType(
      @PathVariable long ontologyId, @RequestBody CreateEntityTypeRequest request) {
    return elementService.addEntityType(ontologyId, request);
  }

  @PatchMapping("/entity-types/{entityTypeId}")
  @RequirePermission("ontology:write")
  public EntityTypeMutation updateEntityType(
      @PathVariable long ontologyId,
      @PathVariable long entityTypeId,
      @RequestBody UpdateEntityTypeRequest request) {
    return elementService.updateEntityType(ontologyId, entityTypeId, request);
  }

  @DeleteMapping("/entity-types/{entityTypeId}")
  @RequirePermission("ontology:write")
  public EntityTypeDeletion deleteEntityType(
      @PathVariable long ontologyId, @PathVariable long entityTypeId) {
    return elementService.deleteEntityType(ontologyId, entityTypeId);
  }

  @PostMapping("/entity-types/{entityTypeId}/properties")
  @RequirePermission("ontology:write")
  public PropertyMutation addProperty(
      @PathVariable long ontologyId,
      @PathVariable long entityTypeId,
      @RequestBody CreatePropertyRequest request) {
    return elementService.addProperty(ontologyId, entityTypeId, request);
  }

  @PatchMapping("/entity-types/{entityTypeId}/properties/{propertyId}")
  @RequirePermission("ontology:write")
  public PropertyMutation updateProperty(
      @PathVariable long ontologyId,
      @PathVariable long entityTypeId,
      @PathVariable long propertyId,
      @RequestBody UpdatePropertyRequest request) {
    return elementService.updateProperty(ontologyId, entityTypeId, propertyId, request);
  }

  @DeleteMapping("/entity-types/{entityTypeId}/properties/{propertyId}")
  @RequirePermission("ontology:write")
  public VersionOnly deleteProperty(
      @PathVariable long ontologyId,
      @PathVariable long entityTypeId,
      @PathVariable long propertyId) {
    return elementService.deleteProperty(ontologyId, entityTypeId, propertyId);
  }

  @PostMapping("/relations")
  @RequirePermission("ontology:write")
  public RelationMutation addRelation(
      @PathVariable long ontologyId, @RequestBody CreateRelationRequest request) {
    return elementService.addRelation(ontologyId, request);
  }

  @PatchMapping("/relations/{relationId}")
  @RequirePermission("ontology:write")
  public RelationMutation updateRelation(
      @PathVariable long ontologyId,
      @PathVariable long relationId,
      @RequestBody UpdateRelationRequest request) {
    return elementService.updateRelation(ontologyId, relationId, request);
  }

  @DeleteMapping("/relations/{relationId}")
  @RequirePermission("ontology:write")
  public VersionOnly deleteRelation(@PathVariable long ontologyId, @PathVariable long relationId) {
    return elementService.deleteRelation(ontologyId, relationId);
  }
}
