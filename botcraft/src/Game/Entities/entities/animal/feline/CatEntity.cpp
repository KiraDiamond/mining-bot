#if PROTOCOL_VERSION > 404 /* > 1.13.2 */
#include "botcraft/Game/Entities/entities/animal/feline/CatEntity.hpp"

#include <mutex>

namespace Botcraft
{
    const std::array<std::string, CatEntity::metadata_count> CatEntity::metadata_names{ {
        "data_type_id",
        "is_lying",
        "relax_state_one",
        "data_collar_color",
#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
        "data_sound_variant_id",
#endif
    } };

    CatEntity::CatEntity()
    {
        // Initialize all metadata with default values
        SetDataTypeId(1);
        SetIsLying(false);
        SetRelaxStateOne(false);
        SetDataCollarColor(14);
#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
        SetDataSoundVariantId(0);
#endif

        // Initialize all attributes with default values
        attributes.insert({ EntityAttribute::Type::MaxHealth, EntityAttribute(EntityAttribute::Type::MaxHealth, 10.0) });
        attributes.insert({ EntityAttribute::Type::MovementSpeed, EntityAttribute(EntityAttribute::Type::MovementSpeed, 0.3) });
        attributes.insert({ EntityAttribute::Type::AttackDamage, EntityAttribute(EntityAttribute::Type::AttackDamage, 3.0) });
    }

    CatEntity::~CatEntity()
    {

    }


    std::string CatEntity::GetName() const
    {
        return "cat";
    }

    EntityType CatEntity::GetType() const
    {
        return EntityType::Cat;
    }


    std::string CatEntity::GetClassName()
    {
        return "cat";
    }

    EntityType CatEntity::GetClassType()
    {
        return EntityType::Cat;
    }


    ProtocolCraft::Json::Value CatEntity::Serialize() const
    {
        ProtocolCraft::Json::Value output = TamableAnimalEntity::Serialize();

        output["metadata"]["data_type_id"] = GetDataTypeId();
        output["metadata"]["is_lying"] = GetIsLying();
        output["metadata"]["relax_state_one"] = GetRelaxStateOne();
        output["metadata"]["data_collar_color"] = GetDataCollarColor();
#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
        output["metadata"]["data_sound_variant_id"] = GetDataSoundVariantId();
#endif

        output["attributes"]["attack_damage"] = GetAttributeAttackDamageValue();


        return output;
    }


    void CatEntity::SetMetadataValue(const int index, const std::any& value)
    {
        if (index < hierarchy_metadata_count)
        {
            TamableAnimalEntity::SetMetadataValue(index, value);
        }
        else if (index - hierarchy_metadata_count < metadata_count)
        {
            std::scoped_lock<std::shared_mutex> lock(entity_mutex);
            metadata[metadata_names[index - hierarchy_metadata_count]] = value;
        }
    }

    int CatEntity::GetDataTypeId() const
    {
        std::shared_lock<std::shared_mutex> lock(entity_mutex);
        return std::any_cast<int>(metadata.at("data_type_id"));
    }

    bool CatEntity::GetIsLying() const
    {
        std::shared_lock<std::shared_mutex> lock(entity_mutex);
        return std::any_cast<bool>(metadata.at("is_lying"));
    }

    bool CatEntity::GetRelaxStateOne() const
    {
        std::shared_lock<std::shared_mutex> lock(entity_mutex);
        return std::any_cast<bool>(metadata.at("relax_state_one"));
    }

    int CatEntity::GetDataCollarColor() const
    {
        std::shared_lock<std::shared_mutex> lock(entity_mutex);
        return std::any_cast<int>(metadata.at("data_collar_color"));
    }

#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
    int CatEntity::GetDataSoundVariantId() const
    {
        std::shared_lock<std::shared_mutex> lock(entity_mutex);
        return std::any_cast<int>(metadata.at("data_sound_variant_id"));
    }
#endif


    void CatEntity::SetDataTypeId(const int data_type_id)
    {
        std::scoped_lock<std::shared_mutex> lock(entity_mutex);
        metadata["data_type_id"] = data_type_id;
    }

    void CatEntity::SetIsLying(const bool is_lying)
    {
        std::scoped_lock<std::shared_mutex> lock(entity_mutex);
        metadata["is_lying"] = is_lying;
    }

    void CatEntity::SetRelaxStateOne(const bool relax_state_one)
    {
        std::scoped_lock<std::shared_mutex> lock(entity_mutex);
        metadata["relax_state_one"] = relax_state_one;
    }

    void CatEntity::SetDataCollarColor(const int data_collar_color)
    {
        std::scoped_lock<std::shared_mutex> lock(entity_mutex);
        metadata["data_collar_color"] = data_collar_color;
    }

#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
    void CatEntity::SetDataSoundVariantId(const int data_sound_variant_id)
    {
        std::scoped_lock<std::shared_mutex> lock(entity_mutex);
        metadata["data_sound_variant_id"] = data_sound_variant_id;
    }
#endif


    double CatEntity::GetAttributeAttackDamageValue() const
    {
        std::shared_lock<std::shared_mutex> lock(entity_mutex);
        return attributes.at(EntityAttribute::Type::AttackDamage).GetValue();
    }


    double CatEntity::GetWidthImpl() const
    {
        return 0.6;
    }

    double CatEntity::GetHeightImpl() const
    {
        return 0.7;
    }

}
#endif
